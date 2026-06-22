# Agent Demo

Production-grade demo tool showcasing the agent-connector debugging pipeline
for SlayTheAmethyst.

## Quick Start

```bash
# 0. Install the bundled TestCrashCard mod (run once, before the game starts)
python scripts/tools/demo/run.py \
  --install-test-crash \
  -DeviceSerial <serial>

# 1. Launch the game with autoplay + agent-connector (skip install if APK already built)
python scripts/tools/main.py sts-harness \
  -Command smoke \
  -LaunchMode mts \
  -Autoplay \
  -SkipInstall \
  -NoStopAfterSmoke \
  -DeviceSerial <serial> \
  -TimeoutSeconds 300

# 2. Run the full 6-stage demo
python scripts/tools/demo/run.py \
  --stages=all \
  -DeviceSerial <serial>

# 3. (Alternative) Run from an already-running game
python scripts/tools/demo/run.py \
  --stages=all \
  --resume \
  -DeviceSerial <serial>
```

## What Each Stage Does

| Stage | Description | Requires game running? | Key output |
|-------|-------------|----------------------|------------|
| `setup` | Verify game process, boot bridge, agent TCP | Yes | `screen-baseline.png` |
| `hotreload` | DUMP_CLASS → CFR decompile → REDEFINE_CLASS round-trip | Yes | `FontHelper.class`, `FontHelper.java` (CFR) |
| `observe` | 3 OBSERVE snapshots → hand / monsters / map JSON | Yes | `observe-{0,1,2}.json`, `summary.json` |
| `play` | Take COMMAND_DRIVEN → wait for combat → PLAY_CARD × 3 → END_TURN → return AUTONOMOUS | Yes (in combat) | `round-*-before.json`, `round-*-after.json` |
| `perf` | Tracing baseline vs `@locals=true` overhead comparison | Yes | `comparison.json` |
| `crash_locals` | Pull `agent_premain.jsonl`, extract crash locals | Yes | `crash-summary.json` |

## Options

| Flag | Default | Description |
|------|---------|-------------|
| `--stages` | `all` | Comma-separated stage IDs: `setup,hotreload,observe,play,perf,crash_locals` |
| `-DeviceSerial` | — | adb device serial |
| `-AgentPort` | `9099` | Agent-connector TCP port |
| `-OutDir` | `demo-artifacts/<timestamp>` | Output directory for report and artifacts |
| `--resume` | `false` | Skip `setup` stage — assume game already running |
| `--no-cfr` | `false` | Skip CFR decompilation in hotreload stage |
| `--install-test-crash` | — | Push bundled `TestCrashCard.jar` to device and enable it. Run once BEFORE the game starts. |

## Bundled Dependencies

The demo ships with a bundled copy of `TestCrashCard.jar` at `demo/testcrash/`.
This mod adds `CrashPermissionCard` (grants crash permission) and `TestCrashCard`
(a COLORLESS Skill that deliberately throws NPE with 5 local variables in scope).

Use `--install-test-crash` to push it to the device's `mods_library/` and enable it.
The mod must be installed and the game restarted for it to take effect.

The `crash_locals` stage depends on this mod (it reads the premain crash capture
from `agent_premain.jsonl`).  Other stages work without it.

## Output Structure

```
demo-artifacts/YYYYMMDD-HHMMSS/
├── report.json                 # Full report — schemaVersion, stages, operations
├── stage-setup/
│   └── screen-baseline.png
├── stage-hotreload/
│   ├── com/megacrit/cardcrawl/helpers/FontHelper.class  ← dumped class bytecode
│   └── com/megacrit/cardcrawl/helpers/FontHelper.java   ← CFR decompiled (if CFR available)
├── stage-observe/
│   ├── observe-0.json
│   ├── observe-1.json
│   ├── observe-2.json
│   └── summary.json
├── stage-play/
│   ├── round-0-before.json
│   ├── round-0-after.json
│   ├── round-1-before.json
│   ├── round-1-after.json
│   ├── round-2-before.json
│   ├── round-2-after.json
│   └── play-summary.json
├── stage-perf/
│   └── comparison.json
└── stage-crash_locals/
    ├── agent_premain.jsonl
    └── crash-summary.json
```

## Reusing Individual Stages

Each stage is an independent class that can be imported and run standalone:

```python
from scripts.tools.demo.demo_runner import DemoRunner, DemoOptions
from scripts.tools.demo.stages.perf import PerfStage

options = DemoOptions(device_serial="localhost:15555")
runner = DemoRunner(options)
runner.initialize()

stage = PerfStage()
result = stage.run(runner, "/tmp/my-perf-test")
print(result["data"]["comparison"])
# → {"baseline": {"avg_ns": 387892, ...}, "with_locals": {...}, "overhead_pct": 17.4}

runner.shutdown()
```

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| `CONNECTION_FAILED` | Game not running or agent not started | Run `smoke` with `-NoStopAfterSmoke` first |
| `NO_EVENTS` in perf | Retransform not supported; class not yet loaded | Agentmain ATTACH retransforms loaded classes; tracing fires on next method call |
| `NO_CRASH` in crash_locals | No crash occurred during autoplay | Ensure `TestCrashCard` mod enabled + `-Autoplay` flag |
| CFR decompile missing | `cfr.jar` not at `scripts/tools/lib/` | Download from benf.org/other/cfr/ or pass `--no-cfr` |
| `UNKNOWN` in observe | Game classes not accessible from agent ClassLoader | Should be resolved after `ReflectionUtil.forName()` falls back to `Instrumentation.getAllLoadedClasses()` |
| `IDLE` in play | Autoplay consumed hand before play stage ran | Known timing issue — demo stages run sequentially; autoplay continues during perf (16s). Run `--stages=setup,observe,play` alone for reliable play test. |
| `hotreload` ERROR on some classes | Class too fundamental to redefine | Pick a non-core class; demo uses `com.megacrit.cardcrawl.helpers.FontHelper` by default. |

## Known Limitations

- **play stage timing**: When the full demo (`--stages=all`) runs with autoplay, the 16-second
  perf stage gives autoplay time to exhaust the hand.  Run play separately for reliable results.
- **Perf events may be zero**: If the traced class is already loaded by the time agentmain
  attaches and retransform is unsupported, no events fire.  Premain auto-attach (`-javaagent
  spec=...`) avoids this by instrumenting classes at load time.
