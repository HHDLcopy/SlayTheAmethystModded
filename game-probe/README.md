# Agent Connector

Java agent (`-javaagent`) that exposes runtime monitoring and instrumentation services over a TCP plain-text line protocol, allowing external tools to attach monitors to the running game JVM.

## Supported monitors

| Type    | Spec prefix | Description |
|---------|------------|-------------|
| tracing | `tracing`  | Bytecode-level method entry/exit tracing via ASM `ClassFileTransformer` |
| state   | `state`    | JVM/game state snapshot (reflective field reads) |
| thread  | `thread`   | Thread dump with counts, states, deadlock detection, CPU time |
| gc      | `gc`       | GC collector stats and heap/non-heap memory usage |
| class   | `class`    | Loaded class counts grouped by package |

## Spec format

```
<type>@<key>=<value>@<key>=<value>...
```

Examples:
- `tracing@classes=com.megacrit.cardcrawl.cards.*@methods=update,applyPowers`
- `thread`
- `gc`

## Protocol

Plain-text line protocol over TCP (default port `9099`).

| Command                        | Response          |
|-------------------------------|--------------------|
| `ATTACH <spec> {"key":"val"}` | `OK <agent_id>`   |
| `DETACH <agent_id>`          | `OK`              |
| `LIST`                        | `AGENTS id:spec:state ...` |
| `STATUS <agent_id>`          | `STATUS id state uptime_ms event_count` |
| `SUBSCRIBE <agent_id>`       | `OK`              |
| `UNSUBSCRIBE <agent_id>`     | `OK`              |
| `QUIT`                        | `BYE`             |

Data events (while subscribed):
```
DATA <agent_id> {"type":"method_entry","class":"...","method":"...","ts":1234}
```

Errors:
```
ERROR <message>
```

## Usage via harness

```bash
# Start smoke test, auto-attach tracing agent on READY
sts-harness smoke -AgentCommand attach -AgentSpec "tracing@classes=com.megacrit.cards.*"

# Manual agent operations (requires game running with agent)
sts-harness agent-attach -AgentSpec "tracing@classes=com.megacrit.cards.*"
sts-harness agent-list
sts-harness agent-status -AgentSpec "tracing"
sts-harness agent-detach -AgentSpec "tracing"
```

## Usage via AgentBridge (Python)

```python
from lib.agent_bridge import AgentBridge

bridge = AgentBridge(port=9099)
bridge.connect()
agent_id = bridge.attach("tracing@classes=com.megacrit.cards.*")
bridge.subscribe_and_capture(agent_id, Path("output.jsonl"), timeout_seconds=30)
bridge.detach(agent_id)
bridge.close()
```

## Build

Part of the Gradle multi-project build. Produces `game-probe.jar` with `Premain-Class` and `Agent-Class` manifest attributes.

The app's Gradle build copies this jar into `components/game_probe/` in generated runtime assets. At launch, the game JVM receives `-javaagent:<path>=port=9099`.

## Architecture

```
External client (Python/CLI)
       │ TCP :9099
       ▼
AgentConnectionManager ── listens on 127.0.0.1
       │ spawns per-connection
       ▼
AgentSession ── parses protocol commands
       │ creates via SpecMonitorRegistry
       ▼
MonitorAgent (tracing/state/thread/gc/class)
       │ emits data through
       ▼
AgentDataChannel (TcpDataChannel) ── writes to session socket
       │
       ▼ (tracing only)
AgentBytecodeBridge ── called from injected bytecode,
                       looks up channel by agentId
```
