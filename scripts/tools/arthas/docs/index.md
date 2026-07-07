# Arthas 本地文档索引

> 从官方文档和源码下载，用于对照验证 scripts/tools/arthas 模块的指令集完整性。
>
> 来源: https://arthas.aliyun.com/doc/ , https://github.com/alibaba/arthas
> 下载日期: 2026-07-07

## 目录结构

```
arthas-docs/
├── index.md                          # 本文件
├── commands-list-cn.md               # 中文命令列表（含分类说明）
├── commands-list-en.md               # 英文命令列表
├── quick-start.md                    # 快速入门
├── quick-start-en.md                 # Quick Start (EN)
├── install-detail.md                 # 安装文档
├── download.md                       # 下载文档
├── advice-class.md                   # 表达式核心变量
├── advice-class-en.md               # Expression core variables (EN)
├── async.md                          # 后台异步任务
├── advanced-use.md                   # 其他特性（异步/日志/Docker/WebConsole等）
├── commands/                         # 各命令详细文档（参数说明、示例）
│   ├── auth.md                       # 鉴权
│   ├── base64.md                     # Base64 编解码
│   ├── cat.md                        # 打印文件
│   ├── classloader.md                # 类加载器查看
│   ├── classloader-metaspace.md      # ClassLoader metaspace 统计
│   ├── cls.md                        # 清屏
│   ├── dashboard.md                  # 系统实时数据面板
│   ├── dump.md                       # 字节码 dump
│   ├── echo.md                       # 打印参数
│   ├── getstatic.md                  # 查看静态属性
│   ├── grep.md                       # 过滤匹配
│   ├── heapdump.md                   # 堆转储
│   ├── help.md                       # 帮助
│   ├── history.md                    # 命令历史
│   ├── jad.md                        # 反编译
│   ├── jfr.md                        # JFR 记录
│   ├── jvm.md                        # JVM 信息
│   ├── keymap.md                     # 快捷键
│   ├── line.md                       # 源码行观测
│   ├── logger.md                     # Logger 查看/修改
│   ├── mbean.md                      # MBean 信息
│   ├── mc.md                         # 内存编译器
│   ├── memory.md                     # JVM 内存信息
│   ├── monitor.md                    # 方法执行监控
│   ├── ognl.md                       # OGNL 表达式执行
│   ├── options.md                    # 全局选项
│   ├── perfcounter.md                # Perf Counter
│   ├── profiler.md                   # 火焰图
│   ├── pwd.md                        # 工作目录
│   ├── quit.md                       # 退出客户端
│   ├── redefine.md                   # redefine 类
│   ├── reset.md                      # 重置增强
│   ├── retransform.md                # retransform 类
│   ├── sc.md                         # 查看类信息
│   ├── session.md                    # 会话信息
│   ├── sm.md                         # 查看方法信息
│   ├── stack.md                      # 调用路径
│   ├── stop.md                       # 关闭 Arthas
│   ├── sysenv.md                     # 环境变量
│   ├── sysprop.md                    # 系统属性
│   ├── tee.md                        # tee 命令
│   ├── thread.md                     # 线程信息
│   ├── trace.md                      # 方法耗时跟踪
│   ├── tt.md                         # 时空隧道
│   ├── version.md                    # 版本号
│   ├── vmoption.md                   # 诊断选项
│   ├── vmtool.md                     # 对象查询/forceGc
│   └── watch.md                      # 方法执行观测
└── source/
    ├── BuiltinCommandPack.java       # Arthas 内置命令注册源码
    └── github-README.md              # GitHub README 摘要
```

## BuiltinCommandPack 注册的指令（共 48 条）

### 基础指令 (basic1000)
| Arthas 命令 | 对应的 Command 类 | README已验证 |
|-------------|-------------------|:----------:|
| `help`      | HelpCommand       |            |
| `auth`      | AuthCommand       |            |
| `keymap`    | KeymapCommand     |            |
| `cls`       | ClsCommand        |            |
| `reset`     | ResetCommand      |            |
| `version`   | VersionCommand    | ✅         |
| `session`   | SessionCommand    |            |
| `sysprop`   | SystemPropertyCommand |         |
| `sysenv`    | SystemEnvCommand  |            |
| `vmoption`  | VMOptionCommand   |            |
| `logger`    | LoggerCommand     |            |
| `history`   | HistoryCommand    |            |
| `cat`       | CatCommand        |            |
| `base64`    | Base64Command     |            |
| `echo`      | EchoCommand       |            |
| `pwd`       | PwdCommand        |            |
| `grep`      | GrepCommand       |            |
| `tee`       | TeeCommand        |            |
| `options`   | OptionsCommand    |            |
| `stop`      | StopCommand       |            |

### 类/方法指令 (klass100)
| Arthas 命令 | 对应的 Command 类 | README已验证 |
|-------------|-------------------|:----------:|
| `sc`        | SearchClassCommand | ✅         |
| `sm`        | SearchMethodCommand |           |
| `classloader` | ClassLoaderCommand |          |
| `jad`       | JadCommand        | ✅         |
| `getstatic` | GetStaticCommand  |            |
| `mc`        | MemoryCompilerCommand |         |
| `redefine`  | RedefineCommand   |            |
| `retransform` | RetransformCommand |          |
| `dump`      | DumpClassCommand  |            |
| `ognl`      | OgnlCommand       | ✅         |

### 监控指令 (monitor200)
| Arthas 命令 | 对应的 Command 类 | README已验证 |
|-------------|-------------------|:----------:|
| `dashboard` | DashboardCommand  | ✅         |
| `thread`    | ThreadCommand     | ✅         |
| `jvm`       | JvmCommand        |            |
| `memory`    | MemoryCommand     |            |
| `perfcounter` | PerfCounterCommand |          |
| `heapdump`  | HeapDumpCommand   | ✅         |
| `stack`     | StackCommand      |            |
| `trace`     | TraceCommand      | ✅         |
| `watch`     | WatchCommand      | ✅         |
| `monitor`   | MonitorCommand    | ✅         |
| `line`      | LineCommand       |            |
| `tt`        | TimeTunnelCommand |            |
| `mbean`     | MBeanCommand      |            |
| `profiler`  | ProfilerCommand   |            |
| `vmtool`    | VmToolCommand     |            |

### 条件编译指令
| Arthas 命令 | 条件 | README已验证 |
|-------------|------|:----------:|
| `classloader-metaspace` | JDK 有 JFR 支持 |     |
| `jfr`       | JDK 有 JFR 支持  |            |

### 隐藏指令
| Arthas 命令 | 说明 |
|-------------|------|
| `july`      | 彩蛋 |
| `thanks`    | 彩蛋 |

### Shell 内建（非 BuiltinCommandPack 注册）
| 功能 | 说明 |
|------|------|
| `quit`/`exit` | 退出当前会话 |
| `jobs`        | 列出后台任务 |
| `kill`        | 终止后台任务 |
| `fg`          | 前台恢复 |
| `bg`          | 后台运行 |
| `>` 重定向    | 输出到文件 |
| `&` 后台运行  | 异步执行 |
| `plaintext`   | 管道：去除 ANSI |
| `wc`          | 管道：行统计 |

## 对照结论

| 项目 | 状态 |
|------|:----:|
| BuiltinCommandPack 注册 | **全部 48 条** |
| ArthasCommandBridge 使用 `emptyList` 不禁用任何命令 | **全部通过** |
| README "已验证功能" 覆盖 | **10/48 ≈ 21%** |
| 未验证但已注册的指令 | **~38 条** |

**说明**: Python 模块 `scripts/tools/arthas/` 是一个通用 Socket 桥，
不包装具体指令（直接透传文本命令到 Arthas shell）。
所有 BuiltinCommandPack 中的指令理论上都可用，
但 README 只记录了 10 条已验证的。其余指令需逐一测试确认在
Android 设备 + MTS ClassLoader 环境下的兼容性。
