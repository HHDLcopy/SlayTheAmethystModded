# Game Probe

Java agent (`-javaagent`) providing game-specific runtime monitoring and
control over a TCP line protocol (default port `9099`).  Loads into the
game JVM via `premain`; also supports `agentmain` attachment.

## Monitors

| Type              | Spec prefix | Description |
|-------------------|-------------|-------------|
| tracing           | `tracing`   | Bytecode-level method entry/exit tracing via ASM `ClassFileTransformer`. Supports crash-locals capture via `@locals=true`. |
| play              | `play`      | Interactive game control via `OBSERVE` (game-state snapshot) and `EXEC` (card play, end turn, etc.) |

## Spec format

```
<type>@<key>=<value>@<key>=<value>...
```

Examples:
- `tracing@classes=com.megacrit.cardcrawl.cards.*@methods=update,applyPowers`
- `tracing@classes=...@methods=...@locals=true` — crash-locals capture enabled
- `play`

## Protocol

Plain-text line protocol over TCP (default port `9099`).

| Command                       | Response          |
|-------------------------------|--------------------|
| `ATTACH <spec> {"key":"val"}` | `OK <monitor_id>` |
| `DETACH <monitor_id>`         | `OK`              |
| `LIST`                        | `MONITORS id:spec:state ...` |
| `STATUS <monitor_id>`         | `STATUS id state uptime_ms event_count` |
| `SUBSCRIBE <monitor_id>`      | `OK`              |
| `UNSUBSCRIBE <monitor_id>`    | `OK`              |
| `OBSERVE`                     | `STATE <json>`    |
| `EXEC <cmd> {"key":"val"}`    | `RESULT <json>`   |
| `PERF_START <monitor_id>`     | `OK`              |
| `PERF_STOP  <monitor_id>`     | `PERF <json>`     |
| `DUMP_CLASS <fqcn>`           | `BYTECODE <b64>`  |
| `REDEFINE_CLASS <b64>`        | `OK`              |
| `LOAD_AGENT <path> <args>`    | `OK`              |
| `QUIT`                        | `BYE`             |

Data events (while subscribed):
```
DATA <monitor_id> {"type":"method_entry","class":"...","method":"...","ts":1234}
```

Errors:
```
ERROR <message>
```

## Build

Part of the Gradle multi-project build. Produces `game-probe.jar` with
`Premain-Class: io.stamethyst.probe.GameProbe`.  The app's Gradle build
copies this JAR into `components/game_probe/` in generated runtime assets.
At launch the game JVM receives `-javaagent:<path>=port=9099`.

## Architecture

```
External client (Python/CLI)
       │ TCP :9099
       ▼
AgentConnectionManager — listens on 127.0.0.1
       │ spawns per-connection
       ▼
AgentSession — parses protocol commands
       │ creates via MonitorRegistry
       ▼
Monitor (tracing / play)
       │ emits data through
       ▼
AgentDataChannel (TcpDataChannel) — writes to session socket
       │
       ▼ (tracing only)
AgentBytecodeBridge — called from injected bytecode,
                      looks up channel by monitorId
```

## Loading external agents (LOAD_AGENT)

The `LOAD_AGENT` command loads a JAR at runtime, creates an isolated
`URLClassLoader` to invoke `agentmain()` on the agent class listed in the
JAR manifest, then adds the JAR to the system classpath.

```
LOAD_AGENT /data/data/io.stamethyst/files/arthas/arthas-bridge.jar port=8099
```

This is how Arthas (via `arthas-bridge`) is embedded under the same JVM.

## Integration

| Module | How it connects |
|--------|----------------|
| `connector/` (Python) | `connect_stream(9099)` → Unix-socket-proxied TCP |
| `scripts/tools/lib/agent_client.py` | Unified Python client for the above |
| `arthas-bridge/` (Java) | Loaded via `LOAD_AGENT`, runs on `:8099` |
| `mods/amethyst-runtime-compat/` | `AutoplayDriver` taps `PlayMonitor` via `Class.forName` |
