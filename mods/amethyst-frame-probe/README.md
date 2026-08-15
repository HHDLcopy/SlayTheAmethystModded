# amethyst-frame-probe

Bundled mod that provides zero-overhead per-frame timing, an in-game bar-chart HUD, and structured JSONL incident output — all behind a single property switch.

## Activation

Enable in Settings → **GPU Resource Diagnostics** (which also turns on **Performance Overlay**). Both toggles must be on; this flips `performanceDeepDiagnostics` in `StsLaunchSpec`, which adds:

```
-Damethyst.gdx.frame_ring=true
-Damethyst.gdx.frame_ring.budget_ms=<1000/targetFps>
```

Nothing runs when `amethyst.gdx.frame_ring` is absent.

## Included fixes / components

### 1. `FrameRingBuffer` (gdx-patch layer)
**What it does**: Zero-allocation ring buffer (1800 slots ≈ 20 s at 90 fps) that records every rendered frame unconditionally: `totalNs`, `renderNs`, `guardianNs`, `reclaimNs`, `swapNs`, heap bytes, SpriteBatch flush count, texture-switch count.
**Symptom addressed**: The old `FrameProfiler` was gated behind a 33 ms threshold; frames taking 12–32 ms (perfectly visible to players on a 90 fps target) were silently dropped.
**Patch class**: `com.badlogic.gdx.backends.lwjgl.FrameRingBuffer` (new class in gdx-patch, registered in `ModRuntimeJarConstants` and `StsDesktopJarPatcher`).

### 2. `AmethystFrameProbe` (mod entry point)
**What it does**: Subscribes to BaseMod `PostUpdateSubscriber` / `PostRenderSubscriber`. On each update tick it drains `FrameRingBuffer` and feeds data to `FrameHud` and `IncidentWriter`.
**Symptom addressed**: No integrated drain point existed; data had to be pulled manually via harness scripts.
**Patch class**: `io.stamethyst.frameprobe.AmethystFrameProbe` (SpireInitializer).

### 3. `FrameHud` (in-game bar chart)
**What it does**: Renders a 180-bar scrolling chart (bottom-left corner) coloured green/yellow/red relative to the configured budget. One-line text summary shows live FPS, last-frame ms, p99 ms, and over-budget count.
**Symptom addressed**: The old 1 Hz overlay could not show individual frame spikes; a 25 ms frame averaged away entirely.
**Patch class**: `io.stamethyst.frameprobe.FrameHud` (no SpirePatch, rendered via PostRenderSubscriber).

### 4. `IncidentWriter` (JSONL output)
**What it does**: Off-render-thread writer. Every frame that exceeds the budget threshold is serialised as one JSONL line to `<stsRoot>/frame-probe-incidents.jsonl`. Previous session file is rotated to `frame-probe-incidents.prev.jsonl`. Fields: `t` (wall clock ms), `frame`, `totalMs`, `renderMs`, `guardianMs`, `reclaimMs`, `swapMs`, `heapMb`, `flushes`, `switches`, plus game context fields from `GameContext` (`room`, `floor`, `act`, `tag`, `action`).
**Symptom addressed**: Old diagnostics required manually parsing `[gdx-frame]` log lines; this produces machine-readable output usable for baseline comparison.
**Patch class**: `io.stamethyst.frameprobe.IncidentWriter` (background daemon thread).

### 5. `FrameProbePatches` (game-event context hooks)
**What it does**: Three SpirePatch2 hooks write game state into `GameContext.INSTANCE`:
- `CardUsePatch` — records the card ID and frame when a card is played (`tag: card:<id>`).
- `RoomTransitionPatch` — records room class, floor, and act on each `nextRoomTransition`.
- `ActionUpdatePatch` — records the last `AbstractGameAction` subclass that started updating.

**Symptom addressed**: Without game context, a slow frame at ms 4200 tells you nothing; with it you see `"tag":"card:Whirlwind","action":"DamageAllEnemiesAction"` and the root cause is obvious.
**Patch class**: `io.stamethyst.frameprobe.FrameProbePatches` (three independent inner patch classes, each addressing one domain).

## Output format

```jsonl
{"t":1723621842000,"frame":9134,"totalMs":18.342,"renderMs":15.210,"guardianMs":0.012,"reclaimMs":0.188,"swapMs":2.844,"heapMb":398,"flushes":312,"switches":87,"room":"MonsterRoom","floor":3,"act":1,"tag":"card:Whirlwind","action":"DamageAllEnemiesAction"}
```

Parse with `jq` or the `tools/perf-harness` schema at `tools/perf-harness/testdata/baseline-run/metrics.json`.

## Design constraints

- **No threshold in the ring**: every frame is written unconditionally; thresholding happens in `FrameHud`/`IncidentWriter` at read time, not write time.
- **Single property switch**: `amethyst.gdx.frame_ring=true` controls all layers. The old five-property scheme (`frame_profiler`, `frame_profiler.stack`, `frame_profiler.slow_ms`, `frame_profiler.summary_frames`, `frame_profiler.stack`) is removed.
- **Render-thread only**: `FrameRingBuffer` has no locks; single writer and reader on the same thread.
- **No patch in a single file per this repo's AGENTS.md rules**: `CardUsePatch`, `RoomTransitionPatch`, and `ActionUpdatePatch` are separate inner classes in `FrameProbePatches.java`, each addressing one distinct domain.
