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

## 文件结构

| 文件 | 职责 |
|------|------|
| `orchestrator.py` | HarnessOrchestrator 主类 |
| `doctor.py` | 环境检查 |
| `install.py` | 构建安装 |
| `run.py` | 启动/停止 |
| `smoke.py` | 冒烟测试 |
| `single_room.py` | 单房间战斗 |
| `startup_cache.py` | 启动缓存分析 |

## 输出

每个命令在 `debug-artifacts/harness/<command>-<timestamp>/` 下：

```
result.json       — 执行报告（schema v2）
screenshot.png    — 截图（如果适用）
logs/             — logcat 日志
```

## 迁移状态

当前 `scripts/tools/lib/sts_harness.py` 中的功能按命令粒度逐步迁移到此模块。
迁移完成前，`sts_harness.py` 保持向后兼容。
