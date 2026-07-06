# Monitor Module

设备监控与数据采集模块。通过 connector 获取设备运行时信息。

## 职责

- 日志采集（流式 logcat）
- 截图（screencap）
- 拉取设备端文件（crash dump、heap dump）
- 性能数据收集

## 依赖

```
monitor (数据采集层)
  └── ConnectorClient → shell, push, pull, logs
```

## 文件结构

| 文件 | 职责 |
|------|------|
| `monitor.py` | DeviceMonitor 主类 |

## 使用

```python
from scripts.tools.monitor.monitor import DeviceMonitor

mon = DeviceMonitor(connector=conn)

# 截图
mon.screenshot("/tmp/screen.png")

# 流式日志
def on_log(event):
    print(f"[{event.tag}] {event.message}")
mon.stream_logs(filter="StSGame", callback=on_log, timeout=30)

# 拉取崩溃文件
mon.pull("/sdcard/Android/data/io.stamethyst/files/crash.dump", "crash.dump")
```
