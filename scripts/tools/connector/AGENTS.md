# Connector — Agent Reference

## 传输层

- Unix socket，默认路径 `~/.sts/connector.sock`
- 纯文本 JSON 行协议，请求/响应配对
- 支持多个并发客户端连接

## 协议格式

### 请求

```json
{"id":"req-1","method":"forward","params":{"port":9099}}
```

- `id` — 可选，用于请求跟踪；省略时收到无序响应
- `method` — 方法名
- `params` — 参数对象

### 响应

```json
{"id":"req-1","result":{"ok":true,"port":9099}}
```

### 错误

```json
{"id":"req-1","error":{"code":-32000,"message":"device offline"}}
```

### 流式事件

```json
{"id":"req-2","event":"log","data":{"tag":"StSGame","message":"..."}}
```

流式命令（如 `logs`）的响应以多个 event 行推送，结束时连接断开。

## 方法参考

### devices

列出可用 adb 设备。

请求：
```json
{"method":"devices"}
```
响应：
```json
{"devices":[{"serial":"localhost:15555","state":"device","model":"Pixel_8"}]}
```

`state` 取值: `device`, `offline`, `unauthorized`, `unknown`

### select

选择目标设备。connector 持有一个 active device，所有命令发往该设备。

请求：
```json
{"method":"select","params":{"serial":"localhost:15555","timeout_ms":10000}}
```
- `timeout_ms` — 等待设备就绪的超时时间（可选，默认 5000）
- `serial` — 设备序列号，或 `"auto"` 自动选择唯一设备

响应：
```json
{"ok":true}
```

错误：如果设备不存在或超时，返回 `-32001`

### status

返回当前选中设备的状态。

请求：
```json
{"method":"status"}
```
响应：
```json
{"serial":"localhost:15555","state":"online","model":"Pixel_8","product":"pixel8","battery":85,"sdk":34}
```
- `state` — `online` | `offline`
- `battery` — 电池百分比（-1 表示未知）

### forward

建立 `adb forward tcp:<port> tcp:<port>`。

**已废弃**。模块不应直接 forward 端口然后自建 TCP 连接。
改用 `connect_stream` 走统一传输。

请求：
```json
{"method":"forward","params":{"port":9099}}
```
响应：
```json
{"ok":true,"port":9099}
```

- 引用计数管理：同一 port 被多次 forward 仅创建一条实际转发
- 连接断开时自动释放该连接持有的所有 forward

### unforward

移除端口转发（减少引用计数）。

请求：
```json
{"method":"unforward","params":{"port":9099}}
```
响应：
```json
{"ok":true}
```

### connect_stream

建立到设备端 TCP 服务的双向透传通道。connector 内部自动管理
adb forward + TCP connect，客户端只需通过当前 Unix socket 收发原始字节。

请求：
```json
{"method":"connect_stream","params":{"port":9099}}
```
响应：
```json
{"stream_id":"s1"}
```

响应返回后，该 Unix socket 连接进入**透传模式**：
- 此后所有原始字节直接转发到设备端 TCP 服务
- 设备端返回的字节直接写回客户端
- 客户端关闭连接时，connector 自动清理对应的 adb forward

典型用法：
```python
# 给 game-probe 发 LIST 命令
conn = ConnectorClient()
conn.connect(); conn.select("localhost:15555")
stream = conn.connect_stream(port=9099)
stream.write(b"LIST\n")
print(stream.readline())  # "AGENTS ..."
stream.close()
```

```python
# 给 arthas-bridge 发 thread 命令
stream = conn.connect_stream(port=8099)
stream.write(b"thread -n 3\n")
for line in stream.read_until("END\n"):
    print(line)
stream.close()
```

注意：`connect_stream` 之后当前连接进入透传模式，不再响应 JSON 请求。
如需同时维持控制通道和多个透传流，应开多条 Unix socket 连接。

### shell

执行 adb shell 命令。

请求：
```json
{"method":"shell","params":{"command":"ps | grep java","timeout_ms":10000}}
```
响应：
```json
{"exit":0,"stdout":"u0_a142   ...","stderr":""}
```

- `timeout_ms` — 超时（可选，默认 30000）
- `stdout` / `stderr` 合并输出

### push

推送文件到设备。

请求：
```json
{"method":"push","params":{"local":"/tmp/arthas-agent.jar","remote":"/sdcard/arthas/arthas-agent.jar"}}
```
响应：
```json
{"ok":true}
```

### pull

从设备拉取文件。

请求：
```json
{"method":"pull","params":{"remote":"/sdcard/log.txt","local":"/tmp/device-log.txt"}}
```
响应：
```json
{"ok":true}
```

### logs

流式获取 logcat 输出。

请求：
```json
{"method":"logs","params":{"filter":"StSGame","since":"5s"}}
```
事件：
```json
{"event":"log","data":{"time":"07-05 12:00:00","pid":1234,"tid":5678,"level":"I","tag":"StSGame","message":"game started"}}
```

- `filter` — logcat tag 过滤
- `since` — 时间偏移（如 `5s`, `10m`）或空字符串表示从当前开始
- 断开连接时停止日志流

### ping

健康检查。

请求：
```json
{"method":"ping"}
```
响应：
```json
{"pong":true}
```

### quit

请求 connector 优雅退出。所有端口转发自动清理。

请求：
```json
{"method":"quit"}
```
响应：
```json
{"ok":true}
```

## 错误码

| code | 含义 |
|------|------|
| -32000 | 通用错误 |
| -32001 | 设备离线或未选择 |
| -32002 | 端口转发失败 |
| -32003 | 命令执行超时 |
| -32004 | 参数校验失败 |

## 连接生命周期

1. 客户端打开 Unix socket
2. 发送请求/接收响应
3. `select` 选择设备（首次需调用）
4. 使用 `shell` / `push` / `pull` / `logs` 等方法
5. 或 `connect_stream` 进入透传模式（此时不再响应 JSON 请求）
6. 关闭连接时 connector 自动释放该连接持有的所有资源

## 客户端库用法

```python
from scripts.tools.connector.client import ConnectorClient

# 控制通道
c = ConnectorClient()
c.connect()
c.select(serial="localhost:15555")

# shell / push / pull
result = c.shell("ps | grep java")

# 透传通道：给 game-probe 发命令
stream = c.connect_stream(port=9099)
stream.write(b"OBSERVE\n")
print(stream.readline())
stream.close()
```
