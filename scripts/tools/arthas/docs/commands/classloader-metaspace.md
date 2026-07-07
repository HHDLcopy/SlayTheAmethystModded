       classloader-metaspace | arthas  

[arthasv4.3.1](/)

[首页](/)

[在线教程在新窗口打开](/doc/arthas-tutorials.html?language=cn&id=arthas-basics)

[文档](/doc/)

[命令列表](/doc/commands.md)

[下载](/doc/download.md)

版本版本

-   [v3.x在新窗口打开](https://arthas.aliyun.com/3.x/)

-   [简体中文](/doc/classloader-metaspace.html)
-   [English](/en/doc/classloader-metaspace.html)

[](https://github.com/alibaba/arthas)

-   文档
    
    -   [简介](/doc/)
        
    -   [快速入门](/doc/quick-start.html)
        
    -   [Arthas Install](/doc/install-detail.html)
        
    -   [下载](/doc/download.html)
        
    -   [表达式核心变量](/doc/advice-class.html)
        
    -   [命令列表](/doc/commands.md)
        
        -   [auth](/doc/auth.html)
            
        -   [base64](/doc/base64.html)
            
        -   [cat](/doc/cat.html)
            
        -   [classloader](/doc/classloader.html)
            
        -   [classloader-metaspace](/doc/classloader-metaspace.html)
            
        -   [cls](/doc/cls.html)
            
        -   [dashboard](/doc/dashboard.html)
            
        -   [dump](/doc/dump.html)
            
        -   [echo](/doc/echo.html)
            
        -   [getstatic](/doc/getstatic.html)
            
        -   [grep](/doc/grep.html)
            
        -   [heapdump](/doc/heapdump.html)
            
        -   [help](/doc/help.html)
            
        -   [history](/doc/history.html)
            
        -   [jad](/doc/jad.html)
            
        -   [jfr](/doc/jfr.html)
            
        -   [jvm](/doc/jvm.html)
            
        -   [keymap](/doc/keymap.html)
            
        -   [line](/doc/line.html)
            
        -   [logger](/doc/logger.html)
            
        -   [mbean](/doc/mbean.html)
            
        -   [mc](/doc/mc.html)
            
        -   [memory](/doc/memory.html)
            
        -   [monitor](/doc/monitor.html)
            
        -   [ognl](/doc/ognl.html)
            
        -   [options](/doc/options.html)
            
        -   [perfcounter](/doc/perfcounter.html)
            
        -   [profiler](/doc/profiler.html)
            
        -   [pwd](/doc/pwd.html)
            
        -   [quit](/doc/quit.html)
            
        -   [redefine](/doc/redefine.html)
            
        -   [reset](/doc/reset.html)
            
        -   [retransform](/doc/retransform.html)
            
        -   [sc](/doc/sc.html)
            
        -   [session](/doc/session.html)
            
        -   [sm](/doc/sm.html)
            
        -   [stack](/doc/stack.html)
            
        -   [stop](/doc/stop.html)
            
        -   [sysenv](/doc/sysenv.html)
            
        -   [sysprop](/doc/sysprop.html)
            
        -   [tee](/doc/tee.html)
            
        -   [thread](/doc/thread.html)
            
        -   [trace](/doc/trace.html)
            
        -   [tt](/doc/tt.html)
            
        -   [version](/doc/version.html)
            
        -   [vmoption](/doc/vmoption.html)
            
        -   [vmtool](/doc/vmtool.html)
            
        -   [watch](/doc/watch.html)
            
    -   [AI 相关](/doc/ai.md)
        
        -   [Arthas MCP Server](/doc/mcp-server.html)
            
    -   [其他特性](/doc/advanced-use.md)
        
        -   [Arthas 后台异步任务](/doc/async.html)
            
        -   [执行结果存日志](/doc/save-log.html)
            
        -   [Docker](/doc/docker.html)
            
        -   [Web Console](/doc/web-console.html)
            
        -   [Arthas Tunnel](/doc/tunnel.html)
            
        -   [IDEA Plugin](/doc/idea-plugin.html)
            
        -   [Arthas Properties](/doc/arthas-properties.html)
            
        -   [以 Java Agent 的方式启动](/doc/agent.html)
            
        -   [Arthas Spring Boot Starter](/doc/spring-boot-starter.html)
            
        -   [加载外部命令](/doc/external-command.html)
            
        -   [Http API](/doc/http-api.html)
            
        -   [批处理功能](/doc/batch-support.html)
            
    -   [FAQ](/doc/faq.html)
        
    -   [用户案例在新窗口打开](https://github.com/alibaba/arthas/issues?q=label%3Auser-case)
        
    -   [Star me at github在新窗口打开](https://github.com/alibaba/arthas)
        
    -   [编译调试/参与贡献在新窗口打开](https://github.com/alibaba/arthas/blob/master/CONTRIBUTING.md)
        
    -   [Release Notes在新窗口打开](https://github.com/alibaba/arthas/releases)
        
    -   [QQ 群/钉钉群](/doc/contact-us.md)
        

目录

# [#](#classloader-metaspace) classloader-metaspace

提示

按 ClassLoader 实例统计 metaspace / class metadata 相关内存。

`classloader-metaspace` 使用 JFR 的 `jdk.ClassLoaderStatistics` 事件采集每个 ClassLoaderData 的 metaspace 统计信息，并把这些统计信息映射回 Arthas 可识别的 ClassLoader hash、类型和显示名。

> 注意：这个命令统计的是 metaspace / class metadata 相关内存，不是从 ClassLoader 可达的所有 Java heap 对象的 retained size。

`classloader-metaspace` 依赖 JFR。JDK 不支持 JFR 时不会注册该命令。

## [#](#参数说明) 参数说明

参数名称

参数说明

`[c:]`

ClassLoader 的 hashcode，格式和 `classloader -c` 一致

`[classLoaderClass:]`

按 ClassLoader 完整类名过滤

`[duration:]`

JFR 采样时长，默认 `2500ms`，支持 `ms`、`s`、`m`，裸数字按毫秒处理

`[period:]`

`jdk.ClassLoaderStatistics` 采样周期，默认 `500ms`，支持 `ms`、`s`、`m`

`[limit:]`

只输出按 `chunkSize` 降序排序后的前 N 行

`[verbose:]`

输出完整诊断列，包括 `classLoaderData`、`hiddenBlockSize` 和 `type`；也可使用 `-v`

## [#](#输出字段) 输出字段

默认终端表格优先展示日常排查需要的核心列。使用 `--verbose` 时会输出完整诊断列，`type` 和 `name` 这类长文本列放在右侧；如果终端宽度不足，长文本列可能被截断或隐藏。

字段

说明

`hash`

Arthas ClassLoader hash

`classes`

JFR `classCount`，已加载类数量

`chunkSize`

该 ClassLoaderData 已分配的 metaspace chunk 总大小

`blockSize`

已使用的 metaspace block 总大小

`name`

显示名，优先使用 JFR name，失败时回退到 `toString()`

`--verbose` 额外输出：

字段

说明

`classLoaderData`

HotSpot 内部 ClassLoaderData 指针

`hiddenBlockSize`

hidden class 使用的 metaspace block 总大小；在 JDK 11 上兼容读取 JFR 的 `anonymousBlockSize`

`type`

ClassLoader 类名

## [#](#使用参考) 使用参考

### [#](#查看所有-classloader-metaspace-统计) 查看所有 ClassLoader metaspace 统计

```
$ classloader-metaspace
 hash      classes  chunkSize  blockSize  name
 68b31f0a  2115     1048576    823296     com.taobao.arthas.agent.ArthasClassloader@68b31f0a
 null      1861     524288     410624     BootstrapClassLoader
Affect(row-cnt:2) cost in 2510 ms.
```

### [#](#按-classloader-类型过滤) 按 ClassLoader 类型过滤

```
$ classloader-metaspace --classLoaderClass demo.TestApp$ModuleClassLoader
 hash      classes  chunkSize  blockSize  name
 6d06d69c  1        6144       1744       order-service's ModuleClassLoader
 7852e922  1        4096       1744       pay-service's ModuleClassLoader
 4e25154f  1        7168       1752       user-service's ModuleClassLoader
```

### [#](#输出完整诊断列) 输出完整诊断列

```
$ classloader-metaspace --classLoaderClass demo.TestApp$ModuleClassLoader --verbose
 hash      classLoaderData     classes  chunkSize  blockSize  hiddenBlockSize  type                            name
 6d06d69c  0x000000014e135010  1        6144       1744       0                demo.TestApp$ModuleClassLoader  order-service's ModuleClassLoader
 7852e922  0x000000013c605640  1        4096       1744       0                demo.TestApp$ModuleClassLoader  pay-service's ModuleClassLoader
 4e25154f  0x000000014c717830  1        7168       1752       0                demo.TestApp$ModuleClassLoader  user-service's ModuleClassLoader
```

### [#](#限制输出行数) 限制输出行数

```
$ classloader-metaspace --limit 20
```

默认按 `chunkSize desc, blockSize desc, name asc` 排序，因此 `--limit` 会保留 metaspace chunk 分配较大的 ClassLoader。

[在 GitHub 上编辑此页在新窗口打开](https://github.com/alibaba/arthas/edit/master/site/docs/doc/classloader-metaspace.md)

Last Updated:

贡献者: hengyunabc

[classloader](/doc/classloader.html) [cls](/doc/cls.html)
