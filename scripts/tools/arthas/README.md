# Arthas Module

Arthas（阿尔萨斯）JVM 诊断工具集成模块。通过 connector daemon 的统一传输通道
进行所有设备通信，不直接开 TCP 连接。

## 职责

- 管理 Arthas JARs 的推送与 game-probe 加载
- 通过 `connector.connect_stream()` 获取到 arthas-bridge 的透传通道
- 封装常用 Arthas 命令为可脚本化接口

## 依赖关系

```
   ┌──────────────────────────┐
   │   arthas CLI / Python    │
   └──────────┬───────────────┘
              │
    ┌─────────┴─────────┐
    │  ConnectorClient  │  ← push, connect_stream(8099)
    └─────────┬─────────┘
              │ Unix socket
    ┌─────────┴──────────┐
    │  connector daemon  │  ← 常驻进程
    └─────────┬──────────┘
              │ adb forward + TCP
    ┌─────────┴──────────────────┐
    │  Device JVM                │
    │  ├── game-probe :9099 │  ← LOAD_AGENT
    │  └── arthas-bridge  :8099  │  ← ServerSocket
    │       ├── ArthasBootstrapCompat  (Apache 2.0, modified)
    │       ├── SocketTerm
    │       └── BridgeSession
    └────────────────────────────┘
```

## 启动序列

### 步骤 1: 推送 JARs 到设备

```python
# 需要三个 JARs：
# /data/data/io.stamethyst/files/arthas/arthas-core.jar  (Arthas 命令引擎)
# /data/data/io.stamethyst/files/arthas/arthas-spy.jar   (Spy API)
# /data/data/io.stamethyst/files/arthas/arthas-agent.jar (SocketTerm + 启动器)
```

### 步骤 2: 通过 game-probe 加载

```
LOAD_AGENT arthas-core.jar    → classpath-only (无 Agent-Class)
LOAD_AGENT arthas-agent.jar port=8099  → agentmain 启动 ServerSocket
```

`ArthasCommandBridge.start()` 内部：
1. 反射调用 `ArthasBootstrap` 原始构造函数（不传端口配置 → bind() 跳过 Netty）
2. 取构造后的 `shellServer`，注册 `BuiltinCommandPack`
3. 设 `arthasBootstrap` 静态单例
4. 启动 `java.net.ServerSocket(:8099)`

### 步骤 3: connector 建立透传

```python
stream = connector.connect_stream(port=8099)
stream.write(b"thread -n 3\n")
for line in stream.read_until(b"$ "):
    print(line)
stream.close()
```

## 协议

纯文本，行分隔。命令以 `\n` 结束，需手动消费 prompt `[arthas@PID]$ `：

```
→ version\n
← 3.6.9\n[arthas@12345]$

→ thread -n 3\n
← "Reference Handler" Id=2 ... WAITING ...
← "Finalizer" Id=3 ... WAITING ...
← [arthas@12345]$

→ quit\n
```

## 启动

```bash
# 确保 connector daemon 在运行
python -m scripts.tools.connector daemon &

# 推送+加载+建立通道（一步到位）
python -m scripts.tools.arthas start

# 交互式 shell
python -m scripts.tools.arthas shell

# 单条命令
python -m scripts.tools.arthas query "thread -n 5"

# 停止
python -m scripts.tools.arthas stop
```

## 文件结构

| 文件 | 职责 |
|------|------|
| `manager.py` | Arthas 生命周期管理：推送 JARs、加载、转发 |
| `shell.py` | Shell 客户端：通过 connect_stream 收发命令 |
| `cli.py` | 命令行入口 |
| `resource/` | arthas-core.jar, arthas-spy.jar, arthas-agent.jar |

## 设备端模块

| 模块 | 说明 |
|------|------|
| `arthas-bridge/` (Java) | 独立 Gradle 模块，实现 Term→Socket 桥接 |
| `ArthasCommandBridge.java` | agentmain 入口，调用 ArthasBootstrapCompat 初始化，启动 ServerSocket |
| `ArthasBootstrapCompat.java` | Arthas 源码修改版（Apache 2.0），构造完整 Bootstrap 跳过 Netty |
| `SocketTerm.java` | Arthas Term 接口的纯 socket 实现 |
| `BridgeSession.java` | 每连接线程：createShell → init → readline → 读命令 → 执行 → 写回 |
| `NOTICE` | Apache 2.0 许可证声明 |
## ArthasBootstrapCompat

`com.taobao.arthas.core.server.ArthasBootstrapCompat` 是 Arthas
源码的修改版本（Apache 2.0 许可）。关键改动：

- 提供 `createWithoutNetty(Instrumentation, Map<String,String>)` 工厂方法
- 调用原始 `ArthasBootstrap(Instrumentation, Map)` 构造函数
- 当 `featureMap` 不含 `arthas.http-port` / `arthas.telnet-port` 时，
  `bind()` 跳过 Netty 服务器创建，只执行 `shellServer.listen()` + `SpyAPI.init()`
- 原版构造函数、`initSpy()`、`initArthasEnvironment()`、`initBeans()` 全部保留
- 不需要字段注入或 Unsafe

详见 `arthas-bridge/NOTICE` 的许可证声明。

## 已验证功能

| 命令 | 结果 |
|------|------|
| `version` | `3.6.9` |
| `thread -n 3` | 完整线程信息 |
| `dashboard -i 1 -n 1` | 实时 CPU/内存面板 |
| `sc -d <class>` | 类搜索 |
| `ognl @Class@method(args)` | 运行时表达式执行（需单引号包裹 `'...'`） |
| `watch <class> <method> '{params,returnObj}' -n 1` | listenerId 注册成功，游戏类 Enhanced 可用 |
| `trace -n 1 <class> <method>` | 命令引擎正常，类增强受 ClassLoader 隔离限制 |
| `monitor -c 1 -n 1 <class> <method>` | 同 trace |
| `heapdump <path>` | 堆转储 OK（需写 app 私有目录 `/data/data/io.stamethyst/files/`） |
## 停止流程

```python
stream.close()
# Arthas JARs 留在设备 classpath，JVM 重启后自动清理
```

## 注意事项

- Arthas 和 game-probe 的 tracing 使用独立的 `ClassFileTransformer`，互不影响
- `reset` 命令撤销所有 Arthas 的 instrument，不影响 game-probe 的 transformer
- arthas-bridge 使用 `java.net.ServerSocket`，不依赖 Netty
- Connector daemon 需先启动
- 在自定义 ClassLoader 中使用 `jad`/`sc` 需要指定 `-c <classloader-hash>`

## Arthas vs 现有功能对照

| Arthas 命令 | 现有 game-probe | 优先级 |
|-------------|---------------------|--------|
| `watch` | TracingMonitor (无参数/返回值过滤) | 高 |
| `trace` | TracingMonitor + PERF | 高 |
| `dashboard` | 已移除 — 委托 Arthas | 中 |
| `thread` | 已移除 — 委托 Arthas | 中 |
| `profiler` | 无 | 低 |
| `heapdump` | 无 | 低 |
| `ognl` | 无 | 中 |

Arthas 补充现有系统缺少的能力，game-probe 保留游戏特有的 OBSERVE/EXEC。
