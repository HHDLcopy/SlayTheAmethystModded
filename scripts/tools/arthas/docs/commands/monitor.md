       monitor | arthas  

[arthasv4.3.1](/)

[首页](/)

[在线教程在新窗口打开](/doc/arthas-tutorials.html?language=cn&id=arthas-basics)

[文档](/doc/)

[命令列表](/doc/commands.md)

[下载](/doc/download.md)

版本版本

-   [v3.x在新窗口打开](https://arthas.aliyun.com/3.x/)

-   [简体中文](/doc/monitor.html)
-   [English](/en/doc/monitor.html)

[](https://github.com/alibaba/arthas)

[首页](/)

[在线教程在新窗口打开](/doc/arthas-tutorials.html?language=cn&id=arthas-basics)

[文档](/doc/)

[命令列表](/doc/commands.md)

[下载](/doc/download.md)

版本版本

-   [v3.x在新窗口打开](https://arthas.aliyun.com/3.x/)

-   [简体中文](/doc/monitor.html)
-   [English](/en/doc/monitor.html)

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

# [#](#monitor) monitor

[`monitor`在线教程在新窗口打开](https://arthas.aliyun.com/doc/arthas-tutorials.html?language=cn&id=command-monitor)

提示

方法执行监控

对匹配 `class-pattern`／`method-pattern`／`condition-express`的类、方法的调用进行监控。

`monitor` 命令是一个非实时返回命令.

实时返回命令是输入之后立即返回，而非实时返回的命令，则是不断的等待目标 Java 进程返回信息，直到用户输入 `Ctrl+C` 为止。

服务端是以任务的形式在后台跑任务，植入的代码随着任务的中止而不会被执行，所以任务关闭后，不会对原有性能产生太大影响，而且原则上，任何 Arthas 命令不会引起原有业务逻辑的改变。

## [#](#监控的维度说明) 监控的维度说明

监控项

说明

timestamp

时间戳

class

Java 类

method

方法（构造方法、普通方法）

total

调用次数

success

成功次数

fail

失败次数

rt

平均 RT

fail-rate

失败率

## [#](#参数说明) 参数说明

方法拥有一个命名参数 `[c:]`，意思是统计周期（cycle of output），拥有一个整型的参数值

参数名称

参数说明

*class-pattern*

类名表达式匹配

*method-pattern*

方法名表达式匹配

*condition-express*

条件表达式

\[E\]

开启正则表达式匹配，默认为通配符匹配

`[c:]`

统计周期，默认值为 60 秒

`--classloader`

指定 classloader hash，只增强该 classloader 加载的类

\[b\]

在**方法调用之前**计算 condition-express

`[m <arg>]`

指定 Class 最大匹配数量，默认值为 50。长格式为`[maxMatch <arg>]`。

## [#](#使用参考) 使用参考

```
$ monitor -c 5 demo.MathGame primeFactors
Press Ctrl+C to abort.
Affect(class-cnt:1 , method-cnt:1) cost in 94 ms.
 timestamp            class          method        total  success  fail  avg-rt(ms)  fail-rate
-----------------------------------------------------------------------------------------------
 2018-12-03 19:06:38  demo.MathGame  primeFactors  5      1        4     1.15        80.00%

 timestamp            class          method        total  success  fail  avg-rt(ms)  fail-rate
-----------------------------------------------------------------------------------------------
 2018-12-03 19:06:43  demo.MathGame  primeFactors  5      3        2     42.29       40.00%

 timestamp            class          method        total  success  fail  avg-rt(ms)  fail-rate
-----------------------------------------------------------------------------------------------
 2018-12-03 19:06:48  demo.MathGame  primeFactors  5      3        2     67.92       40.00%

 timestamp            class          method        total  success  fail  avg-rt(ms)  fail-rate
-----------------------------------------------------------------------------------------------
 2018-12-03 19:06:53  demo.MathGame  primeFactors  5      2        3     0.25        60.00%

 timestamp            class          method        total  success  fail  avg-rt(ms)  fail-rate
-----------------------------------------------------------------------------------------------
 2018-12-03 19:06:58  demo.MathGame  primeFactors  1      1        0     0.45        0.00%

 timestamp            class          method        total  success  fail  avg-rt(ms)  fail-rate
-----------------------------------------------------------------------------------------------
 2018-12-03 19:07:03  demo.MathGame  primeFactors  2      2        0     3182.72     0.00%
```

### [#](#指定-class-最大匹配数量) 指定 Class 最大匹配数量

```
$ monitor -c 1 -m 1 demo.MathGame primeFactors
Press Q or Ctrl+C to abort.
Affect(class count:1 , method count:1) cost in 384 ms, listenerId: 6.
 timestamp            class          method        total  success  fail  avg-rt(ms)  fail-rate
-----------------------------------------------------------------------------------------------
 2022-12-25 21:12:58  demo.MathGame  primeFactors  1      1        0     0.18        0.00%

 timestamp            class          method        total  success  fail  avg-rt(ms)  fail-rate
-----------------------------------------------------------------------------------------------
 2022-12-25 21:12:59  demo.MathGame  primeFactors  0      0        0     0.00       0.00%
```

### [#](#指定-classloader-增强) 指定 ClassLoader 增强

当同名类被多个 classloader 加载时，可以先用 `sc -d` 查看 classloader hash，然后用 `--classloader` 指定增强的 classloader（注意 `-c` 在 monitor 里表示统计周期）：

```
sc -d com.example.Foo
monitor --classloader 3d4eac69 com.example.Foo bar
```

### [#](#计算条件表达式过滤统计结果-方法执行完毕之后) 计算条件表达式过滤统计结果(方法执行完毕之后)

```
monitor -c 5 demo.MathGame primeFactors "params[0] <= 2"
Press Q or Ctrl+C to abort.
Affect(class count: 1 , method count: 1) cost in 19 ms, listenerId: 5
 timestamp            class          method         total  success  fail  avg-rt(ms)  fail-rate
-----------------------------------------------------------------------------------------------
 2020-09-02 09:42:36  demo.MathGame  primeFactors    5       3       2      0.09       40.00%

 timestamp            class          method         total  success  fail  avg-rt(ms)  fail-rate
----------------------------------------------------------------------------------------------
 2020-09-02 09:42:41  demo.MathGame  primeFactors    5       2       3      0.11       60.00%

 timestamp            class          method         total  success  fail  avg-rt(ms)  fail-rate
----------------------------------------------------------------------------------------------
 2020-09-02 09:42:46  demo.MathGame  primeFactors    5       1       4      0.06       80.00%

 timestamp            class          method         total  success  fail  avg-rt(ms)  fail-rate
----------------------------------------------------------------------------------------------
 2020-09-02 09:42:51  demo.MathGame  primeFactors    5       1       4      0.12       80.00%

 timestamp            class          method         total  success  fail  avg-rt(ms)  fail-rate
----------------------------------------------------------------------------------------------
 2020-09-02 09:42:56  demo.MathGame  primeFactors    5       3       2      0.15       40.00%
```

### [#](#计算条件表达式过滤统计结果-方法执行完毕之前) 计算条件表达式过滤统计结果(方法执行完毕之前)

```
monitor -b -c 5 com.test.testes.MathGame primeFactors "params[0] <= 2"
Press Q or Ctrl+C to abort.
Affect(class count: 1 , method count: 1) cost in 21 ms, listenerId: 4
 timestamp            class          method         total  success  fail  avg-rt(ms)  fail-rate
----------------------------------------------------------------------------------------------
 2020-09-02 09:41:57  demo.MathGame  primeFactors    1       0        1      0.10      100.00%

 timestamp            class          method         total  success  fail  avg-rt(ms)  fail-rate
----------------------------------------------------------------------------------------------
 2020-09-02 09:42:02  demo.MathGame  primeFactors    3       0        3      0.06      100.00%

 timestamp            class          method         total  success  fail  avg-rt(ms)  fail-rate
----------------------------------------------------------------------------------------------
 2020-09-02 09:42:07  demo.MathGame  primeFactors    2       0        2      0.06      100.00%

 timestamp            class          method         total  success  fail  avg-rt(ms)  fail-rate
----------------------------------------------------------------------------------------------
 2020-09-02 09:42:12  demo.MathGame  primeFactors    1       0        1      0.05      100.00%

 timestamp            class          method         total  success  fail  avg-rt(ms)  fail-rate
----------------------------------------------------------------------------------------------
 2020-09-02 09:42:17  demo.MathGame  primeFactors    2       0        2      0.10      100.00%
```

[在 GitHub 上编辑此页在新窗口打开](https://github.com/alibaba/arthas/edit/master/site/docs/doc/monitor.md)

Last Updated:

贡献者: hengyunabc, Hollow Man, LHearen, mikawudi

[memory](/doc/memory.html) [ognl](/doc/ognl.html)