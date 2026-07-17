# Harness Module

端到端测试编排器，通过 adb、Gradle 和 JVM agent 完成游戏构建、安装、启动、观测。

## 架构

```
Harness.run()
  └── run_command(ctx, out_dir)  ──→  22 个命令分发
        ├── doctor               → harness/doctor.run_doctor(ctx)
        ├── install              → harness/install.run_install(ctx)
        ├── start / stop         → harness/run.run_start/stop(ctx)
        ├── logs                 → harness/logs.run_logs(ctx, out_dir)
        ├── screenshot           → harness/screenshot.run_screenshot(ctx, out_dir)
        ├── status               → harness/status.run_status(ctx)
        ├── mods / set-mods      → harness/mods.run_mods/run_set_mods(ctx)
        ├── smoke / single-room  → harness/smoke.run_smoke(ctx, out_dir)
        ├── decompil             → harness/decompil.run_decompil(ctx, out_dir)
        ├── agent-attach/detach/list/status → harness/agent.*(ctx, out_dir)
        ├── play                 → harness/play.run_play(ctx, out_dir)
        ├── console              → harness/console.run_console(ctx, out_dir)
        ├── hotreload            → harness/hotreload.run_hotreload(ctx, out_dir)
        ├── perf                 → harness/perf.run_perf(ctx, out_dir)
        └── startup-cache-profile → harness/startup_cache.run_startup_cache_profile(ctx, out_dir)
```

共享模块：
- `_context.py` — `HarnessContext` dataclass 封装可变状态（result, operations, adb_path 等）
- `_runner.py` — `run_native`, `CommandResult`, `adb`, `gradle`, `build_adb_args`
- `_device.py` — device 交互：`resolve_device_sts_root`, `read_remote_sts_text`, `remote_sts_path_state`, `clear_runtime_signals`, logcat 生命周期
- `_status.py` — 状态观测：`harness_status`, `wait_harness_status`, crash markers, boot bridge event 解析

每个命令函数签名为 `(ctx: HarnessContext, ...) -> None` 或返回 `int`。

## game-probe 连接

`agent-*`、`play`、`console`、`hotreload` 和 `perf` 命令通过 adb forward 连接游戏 JVM 中的 game-probe，默认端口为 `9099`。可使用 `-AgentPort <port>` 或 `--agent-port <port>` 覆盖连接端口；该参数只控制 Harness 的连接和端口转发，不会重新配置已运行的 game-probe。

`console` 需要以启用 game-probe 的方式启动游戏，并要求 BaseMod DevConsole 可用。不传命令时进入交互模式；可使用 `-ConsoleCommand "gold 999"` 或 `--console-command "gold 999"` 执行单条命令。

## 文件结构

| 文件 | 职责 |
|------|------|
| `_context.py` | HarnessContext dataclass + set_result_success |
| `_runner.py` | run_native, CommandResult, adb, adb_shell_script, gradle, build_adb_args |
| `_device.py` | resolve_device_sts_root, read_remote_sts_text, remote_sts_root_script, remote_sts_path_state, parse_remote_path_state_output, clear_runtime_signals, harness_logcat_dump, start/stop_logcat_capture |
| `_status.py` | harness_status, parse_boot_bridge_events, find_crash_marker, find_single_room_result, find_harness_logcat_crash, last_non_blank_line, extract_startup_cache_log_evidence, process_pid_text, package_version_info, desktop_jar_patch_snapshot, wait_harness_status, update_status_harness_logcat |
| `orchestrator.py` | HarnessOrchestrator 独立编排器 |
| `doctor.py` | doctor 命令 |
| `install.py` | install 命令 |
| `run.py` | start + stop 命令 |
| `logs.py` | logs 命令 |
| `screenshot.py` | screenshot 命令 |
| `status.py` | status 命令 |
| `mods.py` | mods + set-mods 命令 |
| `decompil.py` | decompil 命令 |
| `agent.py` | agent-attach/detach/list/status 命令 |
| `play.py` | play 命令 |
| `console.py` | console 命令（BaseMod DevConsole 交互式/单发控制） |
| `hotreload.py` | hotreload 命令 |
| `perf.py` | perf 命令 |
| `smoke.py` | smoke 命令 |
| `single_room.py` | single-room spec 构建 + 设备推送 |
| `startup_cache.py` | startup-cache-profile 命令 |

## 输出

每个命令在 `debug-artifacts/harness/<command>-<timestamp>/` 下输出 `result.json`。
