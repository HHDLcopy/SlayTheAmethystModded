# Agent Demo — Agent Reference

Reference for LLM agents integrating with this demo tool.  Every API
surface, JSON schema, and call path is documented so an agent can
reproduce the exact protocol exchanges.

## Architecture

```
DemoRunner (demo_runner.py)
  ├── HarnessConnection   ← adb forward + TCP socket
  ├── AgentBridge         ← ATTACH/DETACH/LIST/STATUS/SUBSCRIBE
  ├── AgentProtocol       ← OBSERVE/EXEC/PERF/DUMP/REDEFINE
  └── Stage.run()         ← 6 stages, each self-contained
```

## Protocol Commands

| Command | Format | Response | Python API |
|---------|--------|----------|------------|
| `ATTACH` | `ATTACH <spec> <json>` | `OK <id>` or `ERROR <msg>` | `AgentBridge.attach(spec)` |
| `DETACH` | `DETACH <id>` | `OK` or `ERROR <msg>` | `AgentBridge.detach(id)` |
| `LIST` | `LIST` | `AGENTS id:spec:state ...` | `AgentBridge.list_agents()` |
| `STATUS` | `STATUS <id>` | `STATUS id state uptime count` | `AgentBridge.status(id)` |
| `SUBSCRIBE` | `SUBSCRIBE <id>` | `OK` | `AgentBridge.subscribe_and_capture()` |
| `OBSERVE` | `OBSERVE` | `STATE <json>` | `AgentProtocol.observe()` |
| `EXEC` | `EXEC <cmd> <json>` | `RESULT <json>` or `ERROR` | `AgentProtocol.execute()` |
| `PERF_START` | `PERF_START <id>` | `OK` | `AgentProtocol.perf_start(id)` |
| `PERF_STOP` | `PERF_STOP <id>` | `PERF <json>` | `AgentProtocol.perf_stop(id)` |
| `DUMP_CLASS` | `DUMP_CLASS <fqcn>` | `BYTECODE <base64>` or `ERROR` | `AgentProtocol.dump_class(fqcn)` |
| `REDEFINE_CLASS` | `REDEFINE_CLASS <base64>` | `OK` or `ERROR` | `AgentProtocol.redefine_class(bytes)` |

## OBSERVE Response Schema

```json
{
  "mode": "GAMEPLAY",
  "screen": "NONE",
  "isScreenUp": false,
  "room": { "type": "MonsterRoom", "phase": "COMBAT" },
  "combat": {
    "active": true,
    "player":   { "hp": 72, "maxHp": 75, "block": 5 },
    "energy": 3,
    "hand": [
      { "index": 0, "id": "Strike_R",   "cost": 1 },
      { "index": 1, "id": "Defend_R",    "cost": 1 },
      { "index": 2, "id": "testcrashcard", "cost": 0 }
    ],
    "monsters": [
      { "index": 0, "id": "JawWorm",  "hp": 42, "maxHp": 45, "block": 0, "dead": false },
      { "index": 1, "id": "Cultist",  "hp": 48, "maxHp": 50, "block": 6, "dead": false }
    ]
  },
  "map": {
    "available": true,
    "current":  { "x": 2, "y": 3 },
    "reachable": [
      { "x": 1, "y": 4, "type": "MonsterRoom" },
      { "x": 3, "y": 4, "type": "RestRoom" }
    ]
  }
}
```

## EXEC Commands

| `EXEC <cmd>` | Params | What happens on the game thread |
|---|---|---|
| `PLAY_CARD` | `{}` | `AutoplayHook.playRandomCard()` — picks random playable card, plays it on a random alive monster |
| `END_TURN` | `{}` | `GameActionManager.callEndTurnEarlySequence()` |
| `PRESS_PROCEED` | `{}` | `overlayMenu.proceedButton.hb.clicked = true` |
| `SKIP_ROOM` | `{}` | `room.phase = COMPLETE; waitTimer = 0; pressProceed()` |
| `WAIT` | `{"ms": 500}` | `Thread.sleep(ms)` on game thread (blocks 1 tick) |
| `MODE_COMMAND` | `{"mode":"COMMAND_DRIVEN"}` / `{"mode":"AUTONOMOUS"}` | Switches play mode. `COMMAND_DRIVEN` stops autonomous autoplay; `AUTONOMOUS` resumes it |

EXEC response: `RESULT {"queued":true,"command":"PLAY_CARD","queueSize":0}`

### Agent Play Mode Protocol

When the play monitor is attached, the autoplay driver reads its mode every tick:

```
mode = AUTONOMOUS (default)
  → Autoplay runs normally; agent commands are consumed as a side-effect
  → Agent can OBSERVE without interfering with autonomous decisions

mode = COMMAND_DRIVEN
  → Autoplay tick only consumes the command queue
  → No autonomous actions (main menu / map / combat / rewards) are taken
  → Returns to AUTONOMOUS when the play monitor is detached
```

When `-Damethyst.autoplay.wait_for_agent=true` is passed (harness sets this automatically
with `-Autoplay`), the driver makes **zero** autonomous decisions until the play monitor
connects — keeping the game in the main menu until the demo starts.

## PERF Schema

`PERF_STOP` response: `PERF <json>`
```json
{
  "elapsed_ms": 8038,
  "events":     242,
  "total_ns":   93870000,
  "avg_ns":     387892,
  "rate_per_sec": 30.1
}
```

## Agent Spec Format

```
tracing@classes=<glob1,glob2>@methods=<m1,m2>@locals=true
```
- `classes` — glob patterns (`com.megacrit.*`)
- `methods` — exact method names, comma-separated
- `locals`  — `true` to capture crash locals in try-catch

Example:
```
tracing@classes=io.stamethyst.compatmod.autoplay.AutoplayDriver@methods=onCardCrawlGameUpdate@locals=true
```

## report.json Schema

```json
{
  "schemaVersion": 1,
  "tool": "demo",
  "startedAt": "2026-06-19T...",
  "endedAt":   "2026-06-19T...",
  "durationMs": 45230,
  "options": {
    "stages": ["all"],
    "deviceSerial": "localhost:15555",
    "agentPort": 9099,
    "resume": false,
    "noCFR": false
  },
  "result": {
    "setup":        { "success": true,  "status": "READY",           "data": {...} },
    "observe":      { "success": true,  "status": "COMBAT_ACTIVE",   "data": {...} },
    "play":         { "success": true,  "status": "PLAYED",          "data": {...} },
    "perf":         { "success": true,  "status": "+17.4%",          "data": {...} },
    "hotreload":    { "success": true,  "status": "REDEFINED",       "data": {...} },
    "crash_locals": { "success": true,  "status": "8 locals captured", "data": {...} }
  },
  "operations": [
    { "command": "adb -s ... forward tcp:9099 tcp:9099", "exitCode": 0, ... }
  ]
}
```

## Error Strategy

- Every stage is wrapped in `try/except` inside `DemoRunner._run_single_stage()`.
- `AgentBridgeError` and generic `Exception` are caught; the stage reports `success: false`.
- One stage failing **does not** skip subsequent stages.
- The report always closes the TCP connection in `shutdown()`.
- `_log_op()` records every protocol exchange into `operations[]` for auditing.

## Adding a New Stage

1. Create `stages/new_stage.py`:
   ```python
   from .base import Stage
   
   class NewStage(Stage):
       id = "new_stage"
       name = "My New Stage"
       def run(self, runner, out_dir):
           proto = runner._proto
           state = proto.observe()
           return {"success": True, "status": "OK", "message": "...", "data": {...}}
   ```

2. Register in `demo_runner.py`:
   ```python
   from .stages.new_stage import NewStage
   # Add to ALL_STAGES dict
   ALL_STAGES = { ..., "new_stage": NewStage() }
   ```

3. Update this AGENTS.md with the new stage's description.

## Dependencies

### Python libraries
- `scripts/tools/lib/harness_connection.py` — TCP connection manager
- `scripts/tools/lib/agent_bridge.py`      — ATTACH/DETACH/STATUS/LIST/SUBSCRIBE
- `scripts/tools/lib/agent_protocol.py`    — OBSERVE/EXEC/PERF/DUMP/REDEFINE
- `scripts/tools/lib/sts_harness.py`       — `repo_root()`, `utc_timestamp()`, `read_local_property()`
- `scripts/tools/lib/cfr.jar`              — CFR decompiler (optional)

### Device mods
- `demo/testcrash/TestCrashCard.jar` — bundled; pushed via `--install-test-crash`
  - `CrashPermissionCard` — 0-cost POWER that grants `CrashPermissionPower`
  - `TestCrashCard` — COLORLESS SKILL, throws NPE with 5 locals when permission is active
  - Required by the `crash_locals` stage (premain `@locals=true` capture)
  - Install: `python demo/run.py --install-test-crash` (once, before harness smoke)

### CLI flags unique to the demo

| Flag | Effect |
|------|--------|
| `--install-test-crash` | Push bundled TestCrashCard.jar to device mods_library/ and enable it. Exits after installation. |
