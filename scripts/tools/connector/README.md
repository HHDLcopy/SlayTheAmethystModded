# Connector

设备通信守护进程，所有工具模块与 Android 设备之间的统一连接层。

## 职责

- 常驻后台，管理设备连接生命周期
- 设备发现与选择（adb devices）
- **TCP 透传代理** — 模块不直接建 TCP 连接，通过 connector 的 TCP channel 收发
- adb 命令代理（shell, push, pull）
- 供其他模块通过 TCP API 访问设备

## 跨平台

全部使用 Python `socket` 标准库，TCP 通信，无文件依赖，支持 Linux / macOS / Windows。
Daemon 启动时在 stdout 打印 `{"port":12345,"token":"abc..."}`；客户端通过环境变量
`STS_CONNECTOR_PORT` 和 `STS_CONNECTOR_TOKEN` 连接运行中的 daemon。

## 依赖关系

```
harness / arthas / autoplay / monitor  (临时进程)
        │
        ▼
   ┌─────────────┐
   │  connector  │  ← TCP 127.0.0.1:<port>
   │  (daemon)   │     常驻进程，stdout 输出连接信息
   └──────┬──────┘
          │ adb
          ▼
    Android Device
    ├── game-probe (:9099)     ← 需 -PdebugMode=true 或 -Pautoplay=true 才启动
    └── arthas-bridge    (:8099)  ← 通过 game-probe 的 LOAD_AGENT 加载
```

game-probe 的启动条件为 `launchMode=mts` 且至少满足其一：`debugMode`, `autoplay`, `forceJvmCrash`, `forceRuntimeCrash`, `performanceDeepDiagnostics`。

- 各模块**不直接调用 adb**，所有设备操作通过 connector
- 各模块**不直接开 TCP 连接**，通过 `connect_stream` 统一走 connector channel
- Connector 启动时自动选择设备，运行中可切换

## 启动

```bash
# 随机端口和 token，输出到 stdout
python -m scripts.tools.connector daemon
# stdout: {"port": 12345, "token": "abc123..."}

# 固定端口和 token（便于配置环境变量）
python -m scripts.tools.connector daemon --port 12345 --token my-secret-token

# 指定 PID 文件便于进程管理
python -m scripts.tools.connector daemon --pid-file /tmp/sts-connector.pid
```

启动后将 stdout 输出的 `port` 和 `token` 设为环境变量：
```bash
export STS_CONNECTOR_PORT=12345
export STS_CONNECTOR_TOKEN=my-secret-token
```

## 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `STS_CONNECTOR_PORT` | Connector daemon 的 TCP 端口 | 必填 |
| `STS_CONNECTOR_TOKEN` | 认证 token | 必填 |
| `STS_TEST_DEVICE` | 集成测试的默认设备序列号 | `auto` |

`scripts/tools/lib/env_device.py` 的 `get_test_device_serial()` 读取 `STS_TEST_DEVICE`，所有集成测试文件和 `HarnessOrchestrator` 均通过该函数取值。

## 认证

客户端连接后先发送 `AUTH <token>\n`，Daemon 验证通过后才进入 JSON 协议交互。认证失败返回错误码 `-32005` 并关闭连接。

## 协议格式

TCP (127.0.0.1)，纯文本 JSON 行协议，请求/响应配对，支持多个并发客户端连接。

### 请求

```json
{"id":"req-1","method":"forward","params":{"port":9099}}
```

### 响应

```json
{"id":"req-1","result":{"ok":true,"port":9099}}
```

### 错误

```json
{"id":"req-1","error":{"code":-32000,"message":"device offline"}}
```

## 方法详述

### devices

```json
{"method":"devices"}
→ {"devices":[{"serial":"localhost:15555","state":"device","model":"Pixel_8"}]}
```

### select

```json
{"method":"select","params":{"serial":"localhost:15555","timeout_ms":10000}}
→ {"ok":true}
```
`serial` 可用 `"auto"` 自动选择第一个设备。

### status

```json
{"method":"status"}
→ {"serial":"localhost:15555","state":"online","model":"Pixel_8"}
```

### forward / unforward

已废弃。改用 `connect_stream`。

### connect_stream

建立到设备端 TCP 服务的双向透传通道。响应返回后当前连接进入透传模式。

```json
{"method":"connect_stream","params":{"port":9099}}
→ {"stream_id":"s1"}
```

```python
conn = ConnectorClient()
conn.connect()
conn.select(get_test_device_serial())
stream = conn.connect_stream(port=9099)
stream.write(b"LIST\n")
print(stream.readline())
stream.close()
```

### shell / push / pull

标准 adb 操作。

```json
{"method":"shell","params":{"command":"ps | grep java","timeout_ms":10000}}
→ {"exit":0,"stdout":"...","stderr":""}

{"method":"push","params":{"local":"/tmp/x.jar","remote":"/sdcard/x.jar"}}
→ {"ok":true}

{"method":"pull","params":{"remote":"/sdcard/log.txt","local":"/tmp/log.txt"}}
→ {"ok":true}
```

### ping / quit

```json
{"method":"ping"} → {"pong":true}
{"method":"quit"} → {"ok":true}
```

## 错误码

| code | 含义 |
|------|------|
| -32000 | 通用错误 |
| -32001 | 设备离线或未选择 |
| -32002 | 端口转发失败 |
| -32003 | 命令执行超时 |
| -32004 | 参数校验失败 |
| -32005 | 认证失败 |

## 客户端库用法

```python
from scripts.tools.connector.client import ConnectorClient
from scripts.tools.lib.env_device import get_test_device_serial

c = ConnectorClient()  # 从 STS_CONNECTOR_PORT / STS_CONNECTOR_TOKEN 环境变量读取
c.connect()
c.select(serial=get_test_device_serial())
result = c.shell("ps | grep java")

stream = c.connect_stream(port=9099)
stream.write(b"OBSERVE\n")
print(stream.readline())
stream.close()
```

## 文件结构

| 文件 | 职责 |
|------|------|
| `daemon.py` | TCP server，请求分发，stream proxy |
| `client.py` | 客户端库，含 Stream 封装 |

## 输出目录

- PID 文件：通过 `--pid-file` 指定
- 无其他持久化文件
