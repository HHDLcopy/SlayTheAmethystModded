# Agent Demo — Agent Reference

Reference for LLM agents integrating with this demo tool.

## Architecture

```
DemoRunner (demo_runner.py)
  ├── ConnectorClient    ← Unix socket → connector daemon
  ├── Stream             ← connect_stream(9099) → game-probe
  ├── AgentClient        ← ATTACH/DETACH/LIST/STATUS/SUBSCRIBE/OBSERVE/EXEC
  └── Stage.run()        ← 6 stages, each self-contained
```

## Protocol Commands

| Command | Format | Response | Python API |
|---------|--------|----------|------------|
| `ATTACH` | `ATTACH <spec> <json>` | `OK <id>` or `ERROR <msg>` | `AgentClient.attach(spec)` |
| `DETACH` | `DETACH <id>` | `OK` or `ERROR <msg>` | `AgentClient.detach(id)` |
| `LIST` | `LIST` | `MONITORS id:spec:state ...` | `AgentClient.send("LIST")` |
| `STATUS` | `STATUS <id>` | `STATUS id state uptime count` | `AgentClient.status(id)` |
| `SUBSCRIBE` | `SUBSCRIBE <id>` | `OK` | `AgentClient.subscribe_and_capture()` |
| `OBSERVE` | `OBSERVE` | `STATE <json>` | `AgentClient.observe()` |
| `EXEC` | `EXEC <cmd> <json>` | `RESULT <json>` or `ERROR` | `AgentClient.execute(cmd, params)` |
| `PERF_START` | `PERF_START <id>` | `OK` | `AgentClient.perf_start(id)` |
| `PERF_STOP` | `PERF_STOP <id>` | `PERF <json>` | `AgentClient.perf_stop(id)` |
| `DUMP_CLASS` | `DUMP_CLASS <fqcn>` | `BYTECODE <base64>` or `ERROR` | `AgentClient.dump_class(fqcn)` |
| `REDEFINE_CLASS` | `REDEFINE_CLASS <base64>` | `OK` or `ERROR` | `AgentClient.redefine_class(bytes)` |

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
| `PLAY_CARD` | `{}` | `AutoplayHook.playRandomCard()` |
| `PLAY_CARD_TARGETED` | `{"cardIndex":0,"monsterIndex":0}` | `AutoplayHook.playCardTargeted(idx, idx)` |
| `END_TURN` | `{}` | `GameActionManager.callEndTurnEarlySequence()` |
| `PRESS_PROCEED` | `{}` | `overlayMenu.proceedButton.hb.clicked = true` |
| `SKIP_ROOM` | `{}` | `room.phase = COMPLETE; waitTimer = 0; pressProceed()` |
| `WAIT` | `{"ms": 500}` | `Thread.sleep(ms)` on game thread |
| `MODE_COMMAND` | `{"mode":"COMMAND_DRIVEN"}` / `{"mode":"AUTONOMOUS"}` | Switches play mode |

EXEC response: `RESULT {"queued":true,"command":"PLAY_CARD","queueSize":0}`

### Agent Play Mode Protocol

When the play monitor is attached, the autoplay driver reads its mode every tick:

```
mode = AUTONOMOUS (default)
  → Autoplay runs normally; agent commands are consumed as a side-effect
  → Agent can OBSERVE without interfering

mode = COMMAND_DRIVEN
  → Autoplay tick only consumes the command queue
  → No autonomous actions (main menu / map / combat / rewards) are taken
  → Returns to AUTONOMOUS when the play monitor is detached
```

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
    { "command": "connector connect_stream 9099", "exitCode": 0, ... }
  ]
}
```

## Error Strategy

- Every stage is wrapped in `try/except` inside `DemoRunner._run_single_stage()`.
- `AgentError` and generic `Exception` are caught; the stage reports `success: false`.
- One stage failing **does not** skip subsequent stages.
- The report always closes the stream in `shutdown()`.
- `_log_op()` records every protocol exchange into `operations[]` for auditing.

## Adding a New Stage

1. Create `stages/new_stage.py`.
2. Register in `demo_runner.py` `ALL_STAGES` dict.
3. Update this AGENTS.md.

## Dependencies

### Python libraries
- `scripts/tools/lib/agent_client.py` — unified game-probe protocol client
- `scripts/tools/connector/client.py` — ConnectorClient + connect_stream
- `scripts/tools/lib/sts_harness.py`  — `repo_root()`, `utc_timestamp()`, `read_local_property()`
- `scripts/tools/lib/cfr.jar`         — CFR decompiler (optional)

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
