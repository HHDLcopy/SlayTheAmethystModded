# Harness Module — Agent Reference

## 与 Connector / AgentClient 的集成

```python
from scripts.tools.connector.client import ConnectorClient
from scripts.tools.lib.agent_client import AgentClient

conn = ConnectorClient()
conn.connect()
conn.select(serial="localhost:15555")

agent = AgentClient(connector=conn, port=9099)
agent.connect()
```

## result.json Schema (v2)

```json
{
  "schemaVersion": 2,
  "tool": "harness",
  "command": "smoke",
  "startedAt": "2026-07-05T12:00:00Z",
  "endedAt": "2026-07-05T12:01:00Z",
  "durationMs": 60000,
  "options": {
    "launchMode": "mts",
    "autoplay": true,
    "deviceSerial": "localhost:15555"
  },
  "result": {
    "success": true,
    "status": "PASSED",
    "message": "All checks passed"
  },
  "operations": [
    {
      "command": "gradle build",
      "exitCode": 0,
      "startedAt": "...",
      "endedAt": "...",
      "durationMs": 30000,
      "outputTail": "BUILD SUCCESSFUL"
    },
    {
      "command": "adb install",
      "exitCode": 0,
      "startedAt": "...",
      "endedAt": "...",
      "durationMs": 5000,
      "outputTail": "Success"
    }
  ]
}
```

## 命令流程

### smoke

```
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│ install  │→│  start   │→│  wait    │→│ observe  │→│  stop    │
│ gradle   │  │ am start │  │ 30s      │  │ OBSERVE  │  │force-stop│
│ assemble │  │          │  │          │  │ command  │  │          │
└──────────┘  └──────────┘  └──────────┘  └──────────┘  └──────────┘
     └─ connector.shell()     └─ agent.observe()
```

### single-room

```
install → start (with autoplay + single-room spec)
  → wait: observe until combat active
  → agent.observe() → verify hand/monsters
  → agent.execute("PLAY_CARD") × n
  → agent.execute("END_TURN")
  → repeat until room complete
  → stop
```

### startup-cache-profile

```
for i in {1..N}:
    start
    wait: observe until main menu
    observe: capture mode/screen
    stop
analyze: startup duration trend, cache hit rate
```

## 错误处理

- 每个步骤的 failure 标记在 `operations[]` 中
- 关键步骤（install, start）失败则终止整个命令
- 非关键步骤（screenshot）失败不影响整体结果
- `result.success` = 所有关键步骤成功

## Gradle 集成

HarnessOrchestrator 支持通过 Gradle 触发：

```bash
# CLI 直接运行
python -m scripts.tools.harness smoke -LaunchMode mts -Autoplay true

# 通过 Gradle 包装
./gradlew :app:stsHarnessSmoke -Pautoplay=true
```

Gradle 包装的实现在 `StsAndroidAppBuildPlugin.kt` 中，通过 `ProcessBuilder` 调用
Python harness 脚本并传递 `-P` 属性作为参数。
