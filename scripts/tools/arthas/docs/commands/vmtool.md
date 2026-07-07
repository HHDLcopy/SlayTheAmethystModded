       vmtool | arthas  

[arthasv4.3.1](/)

[首页](/)

[在线教程在新窗口打开](/doc/arthas-tutorials.html?language=cn&id=arthas-basics)

[文档](/doc/)

[命令列表](/doc/commands.md)

[下载](/doc/download.md)

版本版本

-   [v3.x在新窗口打开](https://arthas.aliyun.com/3.x/)

-   [简体中文](/doc/vmtool.html)
-   [English](/en/doc/vmtool.html)

[](https://github.com/alibaba/arthas)

[首页](/)

[在线教程在新窗口打开](/doc/arthas-tutorials.html?language=cn&id=arthas-basics)

[文档](/doc/)

[命令列表](/doc/commands.md)

[下载](/doc/download.md)

版本版本

-   [v3.x在新窗口打开](https://arthas.aliyun.com/3.x/)

-   [简体中文](/doc/vmtool.html)
-   [English](/en/doc/vmtool.html)

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

# [#](#vmtool) vmtool

提示

@since 3.5.1

[`vmtool`在线教程在新窗口打开](https://arthas.aliyun.com/doc/arthas-tutorials.html?language=cn&id=command-vmtool)

`vmtool` 利用`JVMTI`接口，实现查询内存对象，强制 GC 等功能。

-   [JVM Tool Interface在新窗口打开](https://docs.oracle.com/javase/8/docs/platform/jvmti/jvmti.html)

## [#](#获取对象) 获取对象

```
$ vmtool --action getInstances --className java.lang.String --limit 10
@String[][
    @String[com/taobao/arthas/core/shell/session/Session],
    @String[com.taobao.arthas.core.shell.session.Session],
    @String[com/taobao/arthas/core/shell/session/Session],
    @String[com/taobao/arthas/core/shell/session/Session],
    @String[com/taobao/arthas/core/shell/session/Session.class],
    @String[com/taobao/arthas/core/shell/session/Session.class],
    @String[com/taobao/arthas/core/shell/session/Session.class],
    @String[com/],
    @String[java/util/concurrent/ConcurrentHashMap$ValueIterator],
    @String[java/util/concurrent/locks/LockSupport],
]
```

提示

通过 `--limit`参数，可以限制返回值数量，避免获取超大数据时对 JVM 造成压力。默认值是 10。

## [#](#指定-classloader-name) 指定 classloader name

```
vmtool --action getInstances --classLoaderClass org.springframework.boot.loader.LaunchedURLClassLoader --className org.springframework.context.ApplicationContext
```

## [#](#指定-classloader-hash) 指定 classloader hash

可以通过`sc`命令查找到加载 class 的 classloader。

```
$ sc -d org.springframework.context.ApplicationContext
 class-info        org.springframework.boot.context.embedded.AnnotationConfigEmbeddedWebApplicationContext
 code-source       file:/private/tmp/demo-arthas-spring-boot.jar!/BOOT-INF/lib/spring-boot-1.5.13.RELEASE.jar!/
 name              org.springframework.boot.context.embedded.AnnotationConfigEmbeddedWebApplicationContext
...
 class-loader      +-org.springframework.boot.loader.LaunchedURLClassLoader@19469ea2
                     +-sun.misc.Launcher$AppClassLoader@75b84c92
                       +-sun.misc.Launcher$ExtClassLoader@4f023edb
 classLoaderHash   19469ea2
```

然后用`-c`/`--classloader` 参数指定：

```
vmtool --action getInstances -c 19469ea2 --className org.springframework.context.ApplicationContext
```

## [#](#指定返回结果展开层数) 指定返回结果展开层数

提示

`getInstances` action 返回结果绑定到`instances`变量上，它是数组。

通过 `-x`/`--expand` 参数可以指定结果的展开层次，默认值是 1。

```
vmtool --action getInstances -c 19469ea2 --className org.springframework.context.ApplicationContext -x 2
```

## [#](#执行表达式) 执行表达式

提示

`getInstances` action 返回结果绑定到`instances`变量上，它是数组。可以通过`--express`参数执行指定的表达式。

```
vmtool --action getInstances --classLoaderClass org.springframework.boot.loader.LaunchedURLClassLoader --className org.springframework.context.ApplicationContext --express 'instances[0].getBeanDefinitionNames()'
```

### [#](#过滤对象) 过滤对象

对 `getInstances` 返回的 `instances` 数组，可以继续使用 OGNL 选择表达式 `.{? 条件}` 做过滤，其中 `#this` 表示当前遍历到的对象。

下面的例子使用 `java.lang.Thread`，适合在任意仍在运行的 JVM 里验证过滤表达式。它会筛选出所有非 daemon 线程，并只输出线程名：

```
vmtool --action getInstances --className java.lang.Thread --limit -1 --express 'instances.{? #this.daemon == false}.{name}'
```

如果想直接查看过滤后的对象本身，可以去掉最后的 `.{name}`。

## [#](#强制-gc) 强制 GC

```
vmtool --action forceGc
```

-   可以结合 [`vmoption`](/doc/vmoption.html) 命令动态打开`PrintGC`开关。

## [#](#分析堆内存占用) 分析堆内存占用

`heapAnalyze` 会从 GC Root 可达对象出发，统计各个类的实例数量与占用字节数，并输出占用最大的若干对象与类。

```
$ vmtool --action heapAnalyze --classNum 5 --objectNum 3
```

提示

通过 `--classNum` 参数指定展示的类数量，通过 `--objectNum` 参数指定展示的对象数量。

## [#](#分析对象引用链) 分析对象引用链

`referenceAnalyze` 用于分析某个类的实例对象，并输出占用最大的若干对象及其引用回溯链（从对象回溯到 GC Root），用于辅助定位对象来源。

```
$ vmtool --action referenceAnalyze --className java.lang.String --objectNum 5 --backtraceNum 3
```

提示

-   通过 `--objectNum` 参数指定展示的对象数量
-   通过 `--backtraceNum` 参数指定回溯层数，设置为 `-1` 表示一直回溯到 root，设置为 `0` 表示不输出引用链
-   `--backtraceNum` 小于 `-1` 为非法值
-   `getInstances` 支持的 `--classLoaderClass` / `--classloader` 参数同样适用于 `referenceAnalyze`

## [#](#interrupt-指定线程) interrupt 指定线程

thread id 通过`-t`参数指定，可以使用 `thread`命令获取。

```
vmtool --action interruptThread -t 1
```

## [#](#glibc-释放空闲内存) glibc 释放空闲内存

Linux man page: [malloc_trim在新窗口打开](https://man7.org/linux/man-pages/man3/malloc_trim.3.html)

```
vmtool --action mallocTrim
```

## [#](#glibc-内存状态) glibc 内存状态

内存状态将会输出到应用的 stderr。Linux man page: [malloc_stats在新窗口打开](https://man7.org/linux/man-pages/man3/malloc_stats.3.html)

```
vmtool --action mallocStats
```

输出到 stderr 的内容如下：

```
Arena 0:
system bytes     =     135168
in use bytes     =      74352
Total (incl. mmap):
system bytes     =     135168
in use bytes     =      74352
max mmap regions =          0
max mmap bytes   =          0
```

[在 GitHub 上编辑此页在新窗口打开](https://github.com/alibaba/arthas/edit/master/site/docs/doc/vmtool.md)

Last Updated:

贡献者: hengyunabc

[vmoption](/doc/vmoption.html) [watch](/doc/watch.html)
