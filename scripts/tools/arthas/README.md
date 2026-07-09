# Arthas Module

[Arthas](https://arthas.aliyun.com)（阿尔萨斯）是阿里巴巴开源的 JVM 诊断工具。
本模块将其集成到 SlayTheAmethyst 的 Android 运行时中——通过自定义
`arthas-bridge` 绕过 Netty 依赖，在设备 JVM 的 `localhost:8099` 暴露纯 socket 接口，
Python 客户端通过 `connector` daemon 的 `connect_stream` 透传通道收发命令。

## 快速开始

### 前置条件

1. **游戏以 `debugMode=true` 启动** — game-probe（`:9099`）必须作为 `-javaagent` 加载到 JVM：

   ```bash
   # Gradle（推荐）
   ./gradlew :app:stsStart -PlaunchMode=mts -PdebugMode=true

   # 通过 harness
   python scripts/tools/main.py sts-harness -Command start -LaunchMode mts -DebugMode

   # 直接 am start
   adb shell am start -n io.stamethyst/.LauncherActivity \
     --es io.stamethyst.debug_launch_mode mts \
     --ez io.stamethyst.debug_mode true
   ```

   game-probe 的启动条件为 `launchMode=mts` 且至少满足其一：`-DebugMode`、`-Autoplay`、`-ForceJvmCrash`、`-ForceRuntimeCrash` 或 `performanceDeepDiagnostics`（Launcher 设置）。

2. **设置 connector 端口** — `ConnectorClient` 会自动拉起 daemon，只需通过环境变量指定端口：

   ```bash
   export STS_CONNECTOR_PORT=39999
   ```

   也可手动管理 daemon 生命周期：

   ```bash
   python -m scripts.tools.connector start --port 39999
   ```

3. **设备上已有 Arthas 文件**（由 `manager.py` 自动推送，或手动）：

    ```
     /data/data/io.stamethyst/files/arthas/
       arthas-core.jar          # Arthas 命令引擎（13.5 MB）
       arthas-bridge.jar        # 自定义 SocketTerm + 启动器
       arthas-spy.jar           # Arthas spy 组件
       arthas-agent.jar         # Arthas agent
       libprocfs_cpu.so         # 线程 CPU 使用率 /proc fallback（JNI）
       libasyncProfiler-linux-arm64.so  # async-profiler 3.0 aarch64 .so
    ```

### 启动 Arthas

```bash
# CLI 一步：推送 JARs + .so + 加载 bridge + forward 端口
python -m scripts.tools.arthas start

# 交互式 shell
python -m scripts.tools.arthas shell

# 单条命令
python -m scripts.tools.arthas query "thread -n 5"

# 停止
python -m scripts.tools.arthas stop
```

### 程序化使用

```python
from scripts.tools.connector.client import ConnectorClient
from scripts.tools.lib.agent_client import AgentClient
from scripts.tools.arthas.shell import ArthasShell

conn = ConnectorClient()
conn.connect()
conn.select("auto")

# 加载 bridge
agent = AgentClient(connector=conn, port=9099)
agent.connect()
agent.send("LOAD_AGENT /data/data/io.stamethyst/files/arthas/arthas-core.jar")
agent.send("LOAD_AGENT /data/data/io.stamethyst/files/arthas/arthas-bridge.jar port=8099")
agent.close()

# 与 Arthas 交互
stream = conn.connect_stream(port=8099)
shell = ArthasShell(stream=stream)
print(shell.command("thread -n 3"))
stream.close()
```

详见 `__main__.py` 中的 `_cmd_shell()` 和 `_cmd_query()` 实现。

## 架构

```
Python CLI / 测试脚本
    │
    ▼
ConnectorClient ──TCP──→ connector daemon (:39999)
    │                     │
    │ connect_stream(8099) │ adb forward tcp:8099 → device:8099
    ▼                     ▼
ArthasShell (透传)     Device JVM
                           ├── game-probe :9099 ← LOAD_AGENT
                           └── arthas-bridge :8099 ← ServerSocket
                                ├── ArthasBootstrapCompat (无 Netty)
                                ├── SocketTerm (Term→Socket 实现)
                                └── BridgeSession (per-connection shell)
```

两阶段加载：

```
① LOAD_AGENT arthas-core.jar     → 追加到 system classpath（无 Agent-Class）
② LOAD_AGENT arthas-bridge.jar port=8099  → 反射 agentmain，启动 ServerSocket
```

`ArthasCommandBridge.start()` 内：
1. 通过 `ArthasBootstrapCompat.createWithoutNetty()` 构造 Bootstrap（跳过 Netty bind）
2. 获取 `ShellServer`，注册 `BuiltinCommandPack`（禁用列表为空，**全部 48+ 条命令可用**）
3. 注册 `ClassMetaClassWriterTransformer`（修补 ASM 的类型解析以支持 MTS ClassLoader）
4. 启动 `java.net.ServerSocket(:8099)`，每次 `accept` 创建 `BridgeSession` 线程

## 协议

纯文本行协议。命令以 `\n` 结束，需消费 prompt `[arthas@PID]$ `：

```
→ version\n
← 3.6.9\n[arthas@12345]$

→ thread -n 3\n
← "Foo" Id=2 RUNNABLE ...\n[arthas@12345]$
```

`ArthasShell.command()` 自动处理 drain prompt、发送命令、消费输出和逐行 prompt 解析。

## 命令参考

### JVM 与系统信息

| 命令 | 用途 |
|------|------|
| `dashboard [-i <ms>] [-n <count>]` | 实时数据面板：线程 CPU 使用率 + 堆内存统计 |
| `thread` | 线程列表和堆栈。`-n <N>` 显示 CPU 最高的 N 个线程；`-b` 查找阻塞其他线程的线程 |
| `jvm` | JVM 运行时信息：OS、JDK 版本、类加载统计、GC、编译 |
| `memory` | 堆和非堆内存使用详情：eden、old、survivor、metaspace、codecache |
| `sysenv` | JVM 环境变量 |
| `sysprop` | JVM 系统属性 |
| `vmoption` | 查看诊断相关 VM 选项（如 `PrintGC`、`HeapDumpOnOutOfMemoryError`） |
| `perfcounter` | Perf Counter：编译时间、类加载、GC 次数 |
| `mbean` | MBean 信息 |
| `logger` | 查看 logger 配置和级别。Android 平台 logger 有限，但 root logger 可见 |

### 类与类加载器

| 命令 | 用途 |
|------|------|
| `sc <pattern>` | **Search Class**：搜索已加载的类。`-d` 显示详细（源 JAR、ClassLoader）。`-f` 显示字段 |
| `sm <class>` | **Search Method**：列出类的方法签名 |
| `jad <class>` | 反编译 Java 字节码为源码。游戏类通常需要 `-c <classLoaderHash>` |
| `classloader` | 列出所有 ClassLoader 实例、层级和加载的类数 |
| `classloader-metaspace` | ~~显示 Metaspace 使用情况~~ **由 bridge 补全**，基于 JMX `MemoryPoolMXBean`，显示 Metaspace/CompressedClassSpace 内存用量 |
| `getstatic <class>` | 查看类的静态字段 |
| `dump <class> -d <dir>` | 导出字节码到文件 |
| `ognl <expression>` | 执行 OGNL 表达式。可调用任意静态方法、读取字段、执行计算 |

#### ognl 示例

```bash
ognl '@java.lang.System@getProperty("java.version")'      # 读取系统属性
ognl '@com.megacrit.cardcrawl.dungeons.AbstractDungeon@player'  # 获取 game-probe 之外的任意对象
```

### 类转换与热替换

| 命令 | 用途 |
|------|------|
| `retransform <class-file>` | 通过 `ClassFileTransformer` 链重新转换已加载的类。替换被管道处理，可叠加和还原 |
| `retransform -l` | 列出活动的 retransform entry |
| `retransform --deleteAll` | 删除所有 retransform entry |
| `redefine <class-file>` | 通过 `Instrumentation.redefineClasses` 直接替换类。不能添加/删除字段或方法签名 |
| `mc <file.java>` | 内存中编译 `.java` 为 `.class`。**Android JRE 无 `tools.jar`，此命令不可用**。替代方案：本地编译后 push |

`redefine` 与 `retransform` 冲突——不要同时使用。执行前先 `reset`。

> **`mc` 在 Android 上不可用**。Android 的 OpenJDK 8 运行时缺少 JDK 编译器
> （`javax.tools.JavaCompiler` 在 `tools.jar` 中，Android 未打包）。
> 标准替代方案：本地 `javac` 编译 → `adb push` → `retransform` / `redefine`。

### 方法监控与追踪（字节码增强）

这些命令通过注入字节码切面来观测方法调用。完成后应执行 `reset` 移除增强。
使用 `-n <N>` 限制执行次数，避免生产环境性能影响。

| 命令 | 用途 |
|------|------|
| `watch <class> <method> <expr> [-b] [-e] [-s] [-f] [-x <depth>] [-n <N>]` | 观测方法调用的参数、返回值、抛出异常。`-b` 调用前、`-e` 异常、`-s` 返回、`-f` 结束（默认）。表达式默认为 `{params, target, returnObj}` |
| `trace <class> <method> [-n <N>]` | 跟踪方法执行耗时，显示方法树中每个节点的耗时 |
| `monitor <class> <method> [-c <sec>] [-n <N>]` | 监控调用次数、成功/失败、平均 rt、失败率 |
| `stack <class> <method> [-n <N>]` | 显示触发指定方法的调用者堆栈 |
| `tt -t <class> <method> [-n <N>]` | Time Tunnel：记录每次调用的参数和返回值，可回溯重放 |
| `line <class> <method> <line>` | 观测指定源码行的传入参数和局部变量 |

#### 示例

```bash
# 观测 AbstractCard.update() 的入参和返回值，深度 2，仅触发 1 次
watch com.megacrit.cardcrawl.cards.AbstractCard update "{params,returnObj}" -n 1 -x 2

# 跟踪 CardCrawlGame.render() 的调用链耗时
trace com.megacrit.cardcrawl.core.CardCrawlGame render -n 1

# 每秒统计 AbstractCard.update() 的性能
monitor -c 1 com.megacrit.cardcrawl.cards.AbstractCard update -n 5
```

### Profiler / 堆分析

> **实测状态 (2026-07-09)**：wall/ctimer/itimer/cpu 均可用，`getSamples` 正常返回，bridge 不死。`profiler stop` 正常工作。`getThreadState()` 直接返回 `THREAD_RUNNING`（避免非 HotSpot 线程信号上下文崩溃）。

| 命令 | 用途 |
|------|------|
| `profiler list` | 列出可采样的事件类型（cpu、alloc、lock 等） |
| `profiler start [--event <type>]` | 开始采样。默认事件：`cpu` |
| `profiler stop [--format <fmt>]` | 停止采样并输出。输出写入 `arthas-output/` |
| `profiler status` | 显示 profiler 当前状态（idle / running / stopped） |
| `profiler version` | 显示 async-profiler 版本（当前为 3.0） |
| `heapdump <path>` | 堆转储。Android 上路径必须为应用私有目录（如 `/data/data/io.stamethyst/files/heap.hprof`） |

### 其他命令

| 命令 | 用途 |
|------|------|
| `vmtool --action getInstances --className <class> --limit <N>` | 通过 JVMTI 获取堆中指定类的实例 |
| `vmtool --action forceGc` | 强制 GC |
| `options [<name>] [<value>]` | 查看或设置 Arthas 全局选项（如 `unsafe`、`json-format`） |
| `reset` | 重置 Arthas 增强过的所有类。不影响 game-probe transformer |
| `stop` | 关闭 Arthas 服务端。所有客户端断开，增强的类被 `reset` |
| `session` | 显示当前会话信息 |
| `quit` | 退出当前客户端。其他客户端不受影响 |
| `version` | 显示 Arthas 版本 |
| `help [<command>]` | 显示命令帮助 |
| `keymap` | 快捷键列表 |
| `history` | 命令历史 |
| `cls` | 清屏 |

### 管道与后台任务

Arthas 内置管道支持。示例：

```bash
sm java.lang.String * | grep 'index'       # 搜索方法
thread -n 5 | grep 'RUNNABLE'              # 过滤线程状态
```

后台任务：`command &` 异步运行，`jobs` 查看，`fg`/`bg` 前后台切换，`kill` 终止。

## Android 特定说明

### ClassLoader 规则

MTS（ModTheSpire）会为每个 mod 创建独立的 `URLClassLoader`。
对于加载到这些 classloader 中的类，在 `sc`、`jad`、`watch`、`trace` 等命令中需要显式指定 `-c <classLoaderHash>`：

```bash
sc -d com.megacrit.cardcrawl.cards.AbstractCard    # 获取 hash
jad -c 3d4eac69 com.megacrit.cardcrawl.cards.AbstractCard  # 用指定 classloader 反编译
```

### 字节码增强与 CommonSuperBridge

MTS ClassLoader 隔离会导致 ASM 的 `ClassWriter.getCommonSuperClass()` 解析失败
（一个 classloader 中的类找不到另一个 classloader 中加载的父类）。
`CommonSuperBridge` 通过 `Instrumentation.getAllLoadedClasses()` 在全局范围内解析父类，
每个客户端连接时对已加载的 `ClassMetaClassWriter` 执行 `retransformClasses` 注入该逻辑。

如果 `watch`/`trace`/`monitor` 报告 `Type xxx not present` 错误，
断开客户端连接并重新连接——第二次连接通常能成功完成 retransform。

### Bridge 补全命令

Arthas 3.6.9 较旧，以下命令由 `arthas-bridge` 补充实现：

| 命令 | 说明 | 状态 |
|------|------|------|
| `classloader-metaspace` | `ClassLoaderMetaspaceCommand` 为较高 Arthas 版本新增，3.6.9 JAR 中无该类。Bridge 通过自定义 `MetaspaceCommand`（JMX `MemoryPoolMXBean`）提供替代实现 | ✅ 已验证可用 |

### profiler 修复

async-profiler 3.0 交叉编译为 aarch64 `.so`（`build-async-profiler-so.py`），应用 8 个 patch：

| # | 根因 | 修复 |
|---|------|------|
| 1 | Bionic ELF 重定位 | `musl=false` |
| 2 | VMThread bridge 缺失 (JDK 8 无 pthread TLS) | `tryInitVMThreadFromJvm()` 创建专有 `pthread_key`，读 `eetop` |
| 3 | 信号 handler 无 sigaltstack | `SA_ONSTACK` |
| 4 | `_native_libs` 并发写读竞态 | `_parse_lock` barrier |
| 5 | `libprocfs_cpu.so` ELF 解析 SIGSEGV | 跳过 `parseProgramHeaders` |
| 6 | `getThreadState` 读非 HotSpot 线程 ucontext crash | 直接返回 `THREAD_RUNNING` |
| 7 | `.so` 扁平部署与 `ProfilerCommand` 路径不匹配 | `setupAsyncProfilerFlat()` 加载+反射注入 |
| 8 | SIGSEGV handler 转发 `SIG_DFL` crash | 禁用 `orig_segvHandler` 替换 |

所有 patch 由 `build-async-profiler-so.py` 的 `_patch_source()` 自动应用，git checkout 后 rebuild 即可。

**实测结论 (2026-07-10)**：

| 命令 | 结果 |
|------|------|
| `profiler version / list / status` | ✅ |
| `profiler start --event wall` | ✅ 17353 samples |
| `profiler start --event cpu` | ✅ 60 samples |
| `profiler start --event ctimer` | ✅ 54 samples |
| `profiler start --event itimer` | ✅ 48 samples |
| `profiler start --event lock` | ✅ 可用（autoplay 低竞争） |
| `profiler start --event alloc` | ✅ 12 samples |
| `profiler stop` | ✅ |
| `profiler getSamples` | ✅ |
| `profiler execute 'status'` | ✅ |

### alloc 的修复方式

Pojav JDK 8 的 `libjvm.so` 完全 strip 了符号表。async-profiler 的 `AllocTracer` 需要 `send_allocation_in_new_tlab` / `send_allocation_outside_tlab` 的 C++ mangled 符号来安装二进制断点。

修复：利用 async-profiler 已有的 `.gnu_debuglink` 加载机制——在 CI 构建 JDK 时加 `--with-native-debug-symbols=internal`，提取带 `.symtab` 的 `libjvm.so`（strip DWARF 保留符号表），放置为 `libjvm.debuginfo` 伴生文件。`ElfParser::loadSymbolsUsingDebugLink()` 在 `parseLibraries` 阶段自动加载该文件的符号。

### 不支持的命令

| 命令 | 原因 |
|------|------|
| `jfr` | JDK 8 无 `jdk.jfr.Recording` |
| `mc` | JRE 缺少 `tools.jar`，替代：本地 `javac` → `adb push` → `retransform` |
| `alloc / lock` | 需 JDK debug symbols（Pojav JDK 编译时未包含） |

### 线程 CPU 使用率（`/proc/self/task` fallback）

Android ART 的 `ThreadMXBean.getThreadCpuTime()` 默认返回 0 或 -1，导致 `dashboard`
和 `thread -n N` 的 %CPU 始终为 0。Bridge 启动时会自动加载 JNI 库
`libprocfs_cpu.so` 并通过动态代理注入 `ThreadSampler`：当 JVM 返回无效值时
fallback 读取 `/proc/self/task/<tid>/stat` 的 utime+stime 字段来获取真实线程
CPU 时间。

**构建 .so**：`python3 scripts/tools/arthas/build-procfs-so.py`（需要 NDK 27+，target aarch64）

**部署**：`.so` 由 `manager.py` `start()` 自动推送至 `/data/data/io.stamethyst/files/arthas/libprocfs_cpu.so`，bridge 启动时自动加载。

> 旧版本 `.so` 存放在 `/data/data/io.stamethyst/files/libprocfs_cpu.so`，`start()` 会自动清理该残留文件。无需手动 `chown`/`chmod`——`connector` 的 push 命令已处理权限。

### heapdump

堆转储路径必须是应用私有目录（`/data/data/io.stamethyst/files/`），
因为 SELinux 通常禁止写入其他位置。

```bash
heapdump /data/data/io.stamethyst/files/heap.hprof
```

### Arthas 与 game-probe 共存

Arthas 和 game-probe 的 tracing 使用**独立**的 `ClassFileTransformer`，互不干扰。
`reset` 仅撤销 Arthas 的增强，不影响 game-probe 的 transformer。

## 与 game-probe 对比

| 能力 | game-probe | Arthas |
|------|-----------|--------|
| 游戏状态快照 (OBSERVE) | ✅ | ❌ |
| 游戏命令执行 (EXEC) | ✅ | ❌ |
| 方法参数/返回值观测 | TracingMonitor（无法过滤/格式化） | `watch`（OGNL 表达式，灵活过滤） |
| 方法调用链耗时 | PERF | `trace`（树形展示每个子调用耗时） |
| 线程分析 | ❌ | `thread` |
| 耗时监控 | ❌ | `dashboard` |
| 类搜索/反编译 | ❌ | `sc` / `sm` / `jad` |
| OGNL 表达式执行 | ❌ | `ognl` |
| 火焰图 | ❌ | `profiler` |
| 堆转储 | ❌ | `heapdump` |
| 热替换 | ❌ | `retransform` / `redefine` |

game-probe 保留游戏特有的 `OBSERVE` / `EXEC` 功能，Arthas 补充通用 JVM 诊断能力。

## 故障排除

| 症状 | 可能原因 | 解决 |
|------|---------|------|
| `connect_stream` BrokenPipe | bridge 的 `ShellServer` 已关闭（之前执行过 `stop`） | 重启游戏 → 重新 `LOAD_AGENT bridge.jar` |
| `LOAD_AGENT` 返回 `already bind` | bridge 重复加载 | 重启游戏清理 JVM 状态 |
| `LOAD_AGENT` 返回 `class file version` 错误 | 编译的 JAR 类版本高于设备 JVM（Android 用 JDK 8） | 本地 `javac -source 8 -target 8` 重新编译 |
| `Type xxx not present`（trace/watch） | `CommonSuperBridge` 在首次连接时未成功 retransform | 断开客户端重新连接，第二次通常成功 |
| `ognl` 返回 `null` | 调用的方法返回类型是 `void` | `null` 是正确行为；改用有返回值的方法验证 |
| game-probe 无响应 (`available: false`) | 游戏未以 `debugMode` 或 `autoplay` 启动 | 用 `-PdebugMode=true` 或 `--ez io.stamethyst.debug_mode true` 重启 |

## 实现文件

| 文件 | 职责 |
|------|------|
| `manager.py` | 生命周期管理：推送 JARs + .so → LOAD_AGENT → forward 端口，自动清理旧版残留 |
| `shell.py` | `ArthasShell`：prompt drain、命令发送、输出解析 |
| `cli.py` | `run_shell()` / `run_query()`：Shell/单命令入口 |
| `__main__.py` | CLI 接口：`start`、`shell`、`query`、`stop` |
| `resource/arthas-core.jar` | Arthas 3.6.9 命令引擎 |
| `resource/arthas-bridge.jar` | 自定义 bridge（源码在 `arthas-bridge/`） |
| `resource/arthas-agent.jar` | Arthas agent |
| `resource/arthas-spy.jar` | Arthas spy 组件 |
| `resource/libprocfs_cpu.so` | JNI 库：线程 CPU 时间 `/proc` fallback |
| `build-procfs-so.py` | 构建 `libprocfs_cpu.so`（线程 CPU 使用率 `/proc` fallback） |
| `build-async-profiler-so.py` | 交叉编译 async-profiler 3.0 为 aarch64 `.so`（`.so` 可编译，`System.load()` 时 crash，profiler 命令均不可用） |

### 设备端模块 (`arthas-bridge/`)

| 文件 | 说明 |
|------|------|
| `ArthasCommandBridge.java` | `agentmain` 入口。初始化 Bootstrap，注册命令（含自定义 `MetaspaceCommand`），启动 ServerSocket，加载扁平 `.so` 并反射注入 `ProfilerCommand` |
| `MetaspaceCommand.java` | 自定义 `classloader-metaspace` 命令：通过 JMX `MemoryPoolMXBean` 查询 Metaspace/CompressedClassSpace 使用量。Arthas 3.6.9 不含 `ClassLoaderMetaspaceCommand`（为较高版本新增），由 bridge 提供替代实现 |
| `ArthasBootstrapCompat.java` | Arthas 源码修改版（Apache 2.0）。`createWithoutNetty()` 跳过 Netty，仅执行 `shellServer.listen()` + `SpyAPI.init()` |
| `SocketTerm.java` | `Term` 接口的纯 socket 实现。`readline → write(prompt)`、`feed(line) → 触发 handler` |
| `BridgeSession.java` | 每连接线程。`createShell → init → readline → 读命令 → term.feed → 输出写回 socket` |
| `CommonSuperBridge.java` | 解决 MTS ClassLoader 隔离下的 ASM 类型解析 |
| `ClassMetaClassWriterTransformer.java` | 字节码重写：注入 `CommonSuperBridge` 到 `getCommonSuperClass()` |

## 参考

- [Arthas 官方文档](https://arthas.aliyun.com/doc/commands.html)
- [OGNL 语言指南](https://commons.apache.org/dormant/commons-ognl/language-guide.html)
- [Arthas 表达式核心变量](https://arthas.aliyun.com/doc/advice-class.html)
- 本地离线文档：`docs/commands/`（每个命令一个文件，含完整参数说明）
