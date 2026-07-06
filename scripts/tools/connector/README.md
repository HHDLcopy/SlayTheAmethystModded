# Connector

设备通信守护进程，所有工具模块与 Android 设备之间的统一连接层。

## 职责

- 常驻后台，管理设备连接生命周期
- 设备发现与选择（adb devices）
- 端口转发池管理（引用计数，自动清理）
- **TCP 透传代理** — 模块不直接建 TCP 连接，通过 connector 的 Unix socket 收发
- adb 命令代理（shell, push, pull, logcat）
- 供其他模块通过 Unix socket API 访问设备

## 依赖关系

```
harness / arthas / autoplay / monitor  (临时进程)
        │
        ▼
   ┌─────────────┐
   │  connector  │  ← Unix socket ~/.sts/connector.sock
   │  (daemon)   │     常驻进程
   └──────┬──────┘
          │ adb
          ▼
    Android Device
    ├── game-probe (:9099)
    └── arthas-bridge    (:8099)
```

- 各模块**不直接调用 adb**，所有设备操作通过 connector
- 各模块**不直接开 TCP 连接**，通过 `connect_stream` 统一走 Unix socket
- Connector 启动时自动选择设备，运行中可切换

## 启动

```bash
# 默认：自动选择唯一设备，等待设备就绪
python -m scripts.tools.connector daemon

# 指定设备序列号
python -m scripts.tools.connector daemon --device localhost:15555

# 指定 PID 文件便于进程管理
python -m scripts.tools.connector daemon --device auto --pid-file /tmp/sts-connector.pid
```

## API 方法

通过 Unix socket JSON-line 协议调用，详见 `AGENTS.md`。

| 方法 | 说明 |
|------|------|
| `devices` | 列出 adb devices |
| `select` | 选择目标设备 |
| `status` | 设备在线状态与属性 |
| `shell` | 执行 adb shell 命令 |
| `push` | 推送文件 |
| `pull` | 拉取文件 |
| `logs` | 流式 logcat |
| `connect_stream` | 建立到设备 TCP 服务的双向透传通道 |
| `ping` | 健康检查 |
| `quit` | 请求退出 |

## 文件结构

| 文件 | 职责 |
|------|------|
| `daemon.py` | 守护进程入口，Unix socket server，请求分发，stream proxy |
| `device.py` | 设备发现/选择，adb devices 解析，在线状态监控 |
| `forward.py` | 端口转发池 + TCP connect + 双向透传 |
| `client.py` | Connector 客户端库，含 Stream 封装 |

## 输出目录

- `~/.sts/connector.sock` — Unix socket
- `~/.sts/connector.log` — 运行时日志
- `~/.sts/connector.pid` — PID 文件（可选）
