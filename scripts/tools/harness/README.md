# Harness Module

高层编排模块。不直接管理设备连接（通过 connector），不直接与 JVM agent 通信（通过 agent_client）。
仅组合底层模块完成端到端任务。

## 职责

- 构建、安装、启动、停止游戏
- 组合冒烟测试（smoke）
- 单房间战斗验证（single-room）
- 启动缓存性能分析（startup-cache-profile）
- 环境检查（doctor）

## 依赖关系

```
harness (编排层)
  ├── Gradle (构建 APK)
  ├── ConnectorClient → 设备操作
  └── AgentClient → OBSERVE/EXEC (smoke, single-room 需要)
```
## 集成示例

```python
from scripts.tools.connector.client import ConnectorClient
from scripts.tools.lib.agent_client import AgentClient
from scripts.tools.lib.env_device import get_test_device_serial

conn = ConnectorClient()
conn.connect()
conn.select(serial=get_test_device_serial())

agent = AgentClient(connector=conn, port=9099)
agent.connect()
```

## 命令

| 命令 | 依赖 | 说明 |
|------|------|------|
| `doctor` | 无 | 检查 adb、gradle、设备就绪 |
| `install` | Gradle | 构建 + adb install |
| `start` | connector | 启动游戏（MTS / desktop 模式） |
| `stop` | connector | 停止游戏（am force-stop） |
| `logs` | connector | 流式 logcat |
| `screenshot` | connector | 截图 |
| `smoke` | connector + agent | install → start → observe → screenshot → stop |
| `single-room` | connector + agent | 单房间战斗自动验证 |
| `startup-cache-profile` | connector + agent | 多轮启动缓存分析 |
| `mods` | connector | 查看已安装 mod 列表 |
| `set-mods` | connector | 启用/禁用 mod |
### 命令流程

#### smoke

```
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│ install  │→│  start   │→│  wait    │→│ observe  │→│  stop    │
│ gradle   │  │ am start │  │ 30s      │  │ OBSERVE  │  │force-stop│
│ assemble │  │          │  │          │  │ command  │  │          │
└──────────┘  └──────────┘  └──────────┘  └──────────┘  └──────────┘
     └─ connector.shell()     └─ agent.observe()
```

#### single-room

```
install → start (with autoplay + single-room spec)
  → wait: observe until combat active
  → agent.observe() → verify hand/monsters
  → agent.execute("PLAY_CARD") × n
  → agent.execute("END_TURN")
  → repeat until room complete
  → stop
```

#### startup-cache-profile

```
for i in {1..N}:
    start
    wait: observe until main menu
    observe: capture mode/screen
    stop
analyze: startup duration trend, cache hit rate
```

## 文件结构

| 文件 | 职责 | 状态 |
|------|------|------|
| `_context.py` | HarnessContext dataclass + set_result_success | 已完成 |
| `_runner.py` | run_native, CommandResult, adb, adb_shell_script, gradle, build_adb_args | 已完成 |
| `_device.py` | resolve_device_sts_root, read_remote_sts_text, remote_sts_root_script, remote_sts_path_state, parse_remote_path_state_output, clear_runtime_signals, harness_logcat_dump, start/stop_logcat_capture, logcat lifecycle | 已完成 |
| `_status.py` | harness_status, parse_boot_bridge_events, find_crash_marker, find_single_room_result, find_harness_logcat_crash, last_non_blank_line, extract_startup_cache_log_evidence, process_pid_text, package_version_info, desktop_jar_patch_snapshot, wait_harness_status, update_status_harness_logcat | 已完成 |
| `orchestrator.py` | HarnessOrchestrator 主类 (独立，不影响迁移) | 已有 |
| `doctor.py` | doctor 命令 | 已完成 |
| `install.py` | install 命令 | 已完成 |
| `run.py` | start + stop 命令 | 已完成 |
| `logs.py` | logs 命令 | 已完成 |
| `screenshot.py` | screenshot 命令 | 已完成 |
| `status.py` | status 命令 | 已完成 |
| `mods.py` | mods + set-mods 命令 | 已完成 |
| `decompil.py` | decompil 命令 | 已完成 |
| `agent.py` | agent-attach/detach/list/status 命令 | 已完成 |
| `play.py` | play 命令 | 已完成 |
| `hotreload.py` | hotreload 命令 | 已完成 |
| `perf.py` | perf 命令 | 已完成 |
| `smoke.py` | smoke 命令 | 已完成 |
| `single_room.py` | single-room spec 构建 + 设备推送 | 已完成 |
| `startup_cache.py` | startup-cache-profile 命令 | 已完成 |

## 输出

每个命令在 `debug-artifacts/harness/<command>-<timestamp>/` 下：

```
result.json       — 执行报告（schema v2）
screenshot.png    — 截图（如果适用）
logs/             — logcat 日志
```

### result.json Schema (v2)

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

## 迁移状态

迁移已完成：`Harness.run_command()` 已将全部 21 个命令分发到 `scripts/tools/harness/` 下的独立模块中。
`scripts/tools/lib/sts_harness.py` 中的旧方法保留作为向后兼容，后续逐步移除。

### 架构

```
Harness.run_command()
  ├── [doctor]            → harness/doctor.run_doctor(ctx)
  ├── [install]           → harness/install.run_install(ctx)
  ├── [start]             → harness/run.run_start(ctx)
  ├── [stop]              → harness/run.run_stop(ctx)
  ├── [logs]              → harness/logs.run_logs(ctx, out_dir)
  ├── [screenshot]        → harness/screenshot.run_screenshot(ctx, out_dir)
  ├── [status]            → harness/status.run_status(ctx)
  ├── [mods]              → harness/mods.run_mods(ctx)
  ├── [set-mods]          → harness/mods.run_set_mods(ctx)
  ├── [smoke]             → harness/smoke.run_smoke(ctx, out_dir)
  ├── [decompil]          → harness/decompil.run_decompil(ctx, out_dir)
  ├── [agent-attach]      → harness/agent.run_agent_attach(ctx, out_dir)
  ├── [agent-detach]      → harness/agent.run_agent_detach(ctx, out_dir)
  ├── [agent-list]        → harness/agent.run_agent_list(ctx, out_dir)
  ├── [agent-status]      → harness/agent.run_agent_status(ctx, out_dir)
  ├── [play]              → harness/play.run_play(ctx, out_dir)
  ├── [hotreload]         → harness/hotreload.run_hotreload(ctx, out_dir)
  ├── [perf]              → harness/perf.run_perf(ctx, out_dir)
  ├── [single-room]       → harness/smoke.run_smoke(ctx, out_dir) [autoplay_mode=single_room]
  └── [startup-cache-profile] → harness/startup_cache.run_startup_cache_profile(ctx, out_dir)
```

每个命令函数签名为 `(ctx: HarnessContext, ...) -> None` 或返回 `int`。
`HarnessContext` 封装全部可变状态，通过共享引用与 `Harness` 互通。

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
