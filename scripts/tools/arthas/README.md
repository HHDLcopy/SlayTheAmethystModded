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

## 流程

```
1. connector.push(arthas-core.jar, arthas-bridge.jar → /data/.../arthas/)
2. agent_client.load_agent("arthas-core.jar")           # classpath
3. agent_client.load_agent("arthas-bridge.jar", "port=8099")
   → ArthasBootstrapCompat 构造完整 ArthasBootstrap（跳过 Netty）
   → 注册 BuiltinCommandPack
   → 启动 ServerSocket(:8099)
4. stream = connector.connect_stream(port=8099)          # 透传通道
5. stream.write(b"thread -n 3\n")                        # 发送命令
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
| `resource/` | arthas-core.jar（从 Maven 下载） |

## 设备端模块

| 模块 | 说明 |
|------|------|
| `arthas-bridge/` (Java) | 独立 Gradle 模块，实现 Term→Socket 桥接 |
| `ArthasCommandBridge.java` | agentmain 入口，调用 ArthasBootstrapCompat 初始化，启动 ServerSocket |
| `ArthasBootstrapCompat.java` | Arthas 源码修改版（Apache 2.0），构造完整 Bootstrap 跳过 Netty |
| `SocketTerm.java` | Arthas Term 接口的纯 socket 实现 |
| `BridgeSession.java` | 每连接线程：createShell → init → readline → 读命令 → 执行 → 写回 |
| `NOTICE` | Apache 2.0 许可证声明 |

## 已验证功能

| 命令 | 结果 |
|------|------|
| `version` | `3.6.9` |
| `thread -n 3` | 完整线程信息 |
| `dashboard -i 1 -n 1` | 实时 CPU/内存面板 |
| `sc -d <class>` | 类搜索 |
| `ognl <expression>` | 运行时表达式执行 |

## Arthas vs 现有功能对照

| Arthas 命令 | 现有 game-probe | 优先级 |
|-------------|---------------------|--------|
| `watch` | TracingMonitorAgent (无参数/返回值过滤) | 高 |
| `trace` | TracingMonitorAgent + PERF | 高 |
| `dashboard` | ThreadMonitor / GcMonitor（无按需触发） | 中 |
| `jad` | DUMP_CLASS + CFR | 中 |
| `thread` | ThreadMonitor（无按需触发） | 中 |
| `profiler` | 无 | 低 |
| `heapdump` | 无 | 低 |
| `ognl` | 无 | 中 |

Arthas 补充现有系统缺少的能力，game-probe 保留游戏特有的 OBSERVE/EXEC。
