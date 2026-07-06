# Arthas Module — Agent Reference

## 架构

Arthas 通过独立 `arthas-bridge` Java 模块在设备 JVM 内运行。
bridge 内部调用 `ArthasBootstrapCompat.createWithoutNetty()` 构造
完整 ArthasBootstrap 单例（跳过 Netty 端口绑定），通过
`java.net.ServerSocket` 接受连接。Python 端通过 connector
的 Unix socket 收发命令。

```
Python (host)                          Device JVM
─────────────────────────────    ─────────────────────────────

arthas/shell.py                  arthas-bridge.jar
    │                                 │─ agentmain 加载
    │  connector.connect_stream(8099) │  ServerSocket(:8099)
    │  ─── raw bytes ───────────────► │  ArthasBootstrapCompat
    │  ◄── raw bytes ─────────────── │    → ArthasBootstrap 构造
    │                                 │    → ShellServer + BuiltinCommandPack
connector daemon                     │
    ├── adb forward :8099            │
    └── TCP connect 127.0.0.1:8099   │
```

## 启动序列

### 步骤 1: 推送 JARs 到设备

```python
# 仅需两个 JARs：
# /data/data/io.stamethyst/files/arthas/arthas-core.jar  (Arthas 命令引擎)
# /data/data/io.stamethyst/files/arthas/arthas-bridge.jar (SocketTerm + 启动器)
```

### 步骤 2: 通过 agent-connector 加载

```
LOAD_AGENT arthas-core.jar    → classpath-only (无 Agent-Class)
LOAD_AGENT arthas-bridge.jar port=8099  → agentmain 启动 ServerSocket
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

## Arthas 常用命令参考

| 命令 | 用途 | 已验证 |
|------|------|--------|
| `version` | 版本号 | ✅ `3.6.9` |
| `thread` | 线程信息 | ✅ `thread -n 3` |
| `dashboard` | JVM 实时仪表盘 | ✅ `dashboard -i 1 -n 1` |
| `sc` | 搜索类 | ✅ `sc -d com.megacrit...` |
| `jad` | 反编译 | ⚠️ MCP 类在自定义 ClassLoader，需指定 ClassLoader hash |
| `ognl` | 表达式执行 | ✅ `ognl @System@getProperty("java.version")` |
| `watch` | 方法观察 | 理论可用 |
| `trace` | 调用链 | 理论可用 |
| `monitor` | 调用统计 | 理论可用 |
| `heapdump` | 堆转储 | 理论可用 |
| `profiler` | CPU 采样 | 理论可用 |
| `quit` | 退出 session | ✅ |

## 停止流程

```python
stream.close()
# Arthas JARs 留在设备 classpath，JVM 重启后自动清理
```

## 注意事项

- Arthas 和 agent-connector 的 tracing 使用独立的 `ClassFileTransformer`，互不影响
- `reset` 命令撤销所有 Arthas 的 instrument，不影响 agent-connector 的 transformer
- arthas-bridge 使用 `java.net.ServerSocket`，不依赖 Netty
- Connector daemon 需先启动
- 在自定义 ClassLoader 中使用 `jad`/`sc` 需要指定 `-c <classloader-hash>`
