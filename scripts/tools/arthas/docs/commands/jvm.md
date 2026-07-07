       jvm | arthas  

[arthasv4.3.1](/)

[首页](/)

[在线教程在新窗口打开](/doc/arthas-tutorials.html?language=cn&id=arthas-basics)

[文档](/doc/)

[命令列表](/doc/commands.md)

[下载](/doc/download.md)

版本版本

-   [v3.x在新窗口打开](https://arthas.aliyun.com/3.x/)

-   [简体中文](/doc/jvm.html)
-   [English](/en/doc/jvm.html)

[](https://github.com/alibaba/arthas)

[首页](/)

[在线教程在新窗口打开](/doc/arthas-tutorials.html?language=cn&id=arthas-basics)

[文档](/doc/)

[命令列表](/doc/commands.md)

[下载](/doc/download.md)

版本版本

-   [v3.x在新窗口打开](https://arthas.aliyun.com/3.x/)

-   [简体中文](/doc/jvm.html)
-   [English](/en/doc/jvm.html)

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

# [#](#jvm) jvm

[`jvm`在线教程在新窗口打开](https://arthas.aliyun.com/doc/arthas-tutorials.html?language=cn&id=command-jvm)

提示

查看当前 JVM 信息

## [#](#使用参考) 使用参考

```
$ jvm
RUNTIME
--------------------------------------------------------------------------------------------------------------
 MACHINE-NAME                   37@ff267334bb65
 JVM-START-TIME                 2020-07-23 07:50:36
 MANAGEMENT-SPEC-VERSION        1.2
 SPEC-NAME                      Java Virtual Machine Specification
 SPEC-VENDOR                    Oracle Corporation
 SPEC-VERSION                   1.8
 VM-NAME                        Java HotSpot(TM) 64-Bit Server VM
 VM-VENDOR                      Oracle Corporation
 VM-VERSION                     25.201-b09
 INPUT-ARGUMENTS                []
 CLASS-PATH                     demo-arthas-spring-boot.jar
 BOOT-CLASS-PATH                /usr/lib/jvm/java-8-oracle/jre/lib/resources.jar:/usr/lib/jvm/java-8-oracle/j
                                re/lib/rt.jar:/usr/lib/jvm/java-8-oracle/jre/lib/sunrsasign.jar:/usr/lib/jvm/
                                java-8-oracle/jre/lib/jsse.jar:/usr/lib/jvm/java-8-oracle/jre/lib/jce.jar:/us
                                r/lib/jvm/java-8-oracle/jre/lib/charsets.jar:/usr/lib/jvm/java-8-oracle/jre/l
                                ib/jfr.jar:/usr/lib/jvm/java-8-oracle/jre/classes
 LIBRARY-PATH                   /usr/java/packages/lib/amd64:/usr/lib64:/lib64:/lib:/usr/lib

--------------------------------------------------------------------------------------------------------------
 CLASS-LOADING
--------------------------------------------------------------------------------------------------------------
 LOADED-CLASS-COUNT             7529
 TOTAL-LOADED-CLASS-COUNT       7529
 UNLOADED-CLASS-COUNT           0
 IS-VERBOSE                     false

--------------------------------------------------------------------------------------------------------------
 COMPILATION
--------------------------------------------------------------------------------------------------------------
 NAME                           HotSpot 64-Bit Tiered Compilers
 TOTAL-COMPILE-TIME             14921(ms)

--------------------------------------------------------------------------------------------------------------
 GARBAGE-COLLECTORS
--------------------------------------------------------------------------------------------------------------
 PS Scavenge                            name : PS Scavenge
 [count/time (ms)]                      collectionCount : 7
                                        collectionTime : 68

 PS MarkSweep                           name : PS MarkSweep
 [count/time (ms)]                      collectionCount : 1
                                        collectionTime : 47

--------------------------------------------------------------------------------------------------------------
 MEMORY-MANAGERS
--------------------------------------------------------------------------------------------------------------
 CodeCacheManager               Code Cache

 Metaspace Manager              Metaspace
                                Compressed Class Space

 Copy                           Eden Space
                                Survivor Space

 MarkSweepCompact               Eden Space
                                Survivor Space
                                Tenured Gen


--------------------------------------------------------------------------------------------------------------
 MEMORY
--------------------------------------------------------------------------------------------------------------
 HEAP-MEMORY-USAGE                      init : 268435456(256.0 MiB)
 [memory in bytes]                      used : 18039504(17.2 MiB)
                                        committed : 181403648(173.0 MiB)
                                        max : 3817865216(3.6 GiB)

 NO-HEAP-MEMORY-USAGE                   init : 2555904(2.4 MiB)
 [memory in bytes]                      used : 33926216(32.4 MiB)
                                        committed : 35176448(33.5 MiB)
                                        max : -1(-1 B)

--------------------------------------------------------------------------------------------------------------
 OPERATING-SYSTEM
--------------------------------------------------------------------------------------------------------------
 OS                             Linux
 ARCH                           amd64
 PROCESSORS-COUNT               3
 LOAD-AVERAGE                   29.53
 VERSION                        4.15.0-52-generic

--------------------------------------------------------------------------------------------------------------
 THREAD
--------------------------------------------------------------------------------------------------------------
 COUNT                          30
 DAEMON-COUNT                   24
 PEAK-COUNT                     31
 STARTED-COUNT                  36
 DEADLOCK-COUNT                 0

--------------------------------------------------------------------------------------------------------------
 FILE-DESCRIPTOR
--------------------------------------------------------------------------------------------------------------
 MAX-FILE-DESCRIPTOR-COUNT      1048576
 OPEN-FILE-DESCRIPTOR-COUNT     100
Affect(row-cnt:0) cost in 88 ms.
```

## [#](#thread-相关) THREAD 相关

-   COUNT: JVM 当前活跃的线程数
-   DAEMON-COUNT: JVM 当前活跃的守护线程数
-   PEAK-COUNT: 从 JVM 启动开始曾经活着的最大线程数
-   STARTED-COUNT: 从 JVM 启动开始总共启动过的线程次数
-   DEADLOCK-COUNT: JVM 当前死锁的线程数

## [#](#文件描述符相关) 文件描述符相关

-   MAX-FILE-DESCRIPTOR-COUNT：JVM 进程最大可以打开的文件描述符数
-   OPEN-FILE-DESCRIPTOR-COUNT：JVM 当前打开的文件描述符数

[在 GitHub 上编辑此页在新窗口打开](https://github.com/alibaba/arthas/edit/master/site/docs/doc/jvm.md)

Last Updated:

贡献者: hengyunabc, bohr.qiu, Hollow Man

[jfr](/doc/jfr.html) [keymap](/doc/keymap.html)