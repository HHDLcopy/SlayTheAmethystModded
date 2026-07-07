       sc | arthas  

[arthasv4.3.1](/)

[首页](/)

[在线教程在新窗口打开](/doc/arthas-tutorials.html?language=cn&id=arthas-basics)

[文档](/doc/)

[命令列表](/doc/commands.md)

[下载](/doc/download.md)

版本版本

-   [v3.x在新窗口打开](https://arthas.aliyun.com/3.x/)

-   [简体中文](/doc/sc.html)
-   [English](/en/doc/sc.html)

[](https://github.com/alibaba/arthas)

[首页](/)

[在线教程在新窗口打开](/doc/arthas-tutorials.html?language=cn&id=arthas-basics)

[文档](/doc/)

[命令列表](/doc/commands.md)

[下载](/doc/download.md)

版本版本

-   [v3.x在新窗口打开](https://arthas.aliyun.com/3.x/)

-   [简体中文](/doc/sc.html)
-   [English](/en/doc/sc.html)

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

# [#](#sc) sc

[`sc`在线教程在新窗口打开](https://arthas.aliyun.com/doc/arthas-tutorials?language=cn&id=command-sc)

提示

查看 JVM 已加载的类信息

"Search-Class" 的简写，这个命令能搜索出所有已经加载到 JVM 中的 Class 信息，这个命令支持的参数有 `[d]`、`[E]`、`[f]` 和 `[x:]`。

## [#](#参数说明) 参数说明

参数名称

参数说明

*class-pattern*

类名表达式匹配

*method-pattern*

方法名表达式匹配

\[d\]

输出当前类的详细信息，包括这个类所加载的原始文件来源、类的声明、加载的 ClassLoader 等详细信息。  
如果一个类被多个 ClassLoader 所加载，则会出现多次

\[E\]

开启正则表达式匹配，默认为通配符匹配

\[f\]

输出当前类的成员变量信息（需要配合参数-d 一起使用）

\[x:\]

指定输出静态变量时属性的遍历深度，默认为 0，即直接使用 `toString` 输出

`[c:]`

指定 class 的 ClassLoader 的 hashcode

`[classLoaderClass:]`

指定执行表达式的 ClassLoader 的 class name

`[n:]`

具有详细信息的匹配类的最大数量（默认为 100）

`[cs <arg>]`

指定 class 的 ClassLoader#toString() 返回值。长格式`[classLoaderStr <arg>]`

提示

class-pattern 支持全限定名，如 com.taobao.test.AAA，也支持 com/taobao/test/AAA 这样的格式，这样，我们从异常堆栈里面把类名拷贝过来的时候，不需要在手动把`/`替换为`.`啦。

提示

sc 默认开启了子类匹配功能，也就是说所有当前类的子类也会被搜索出来，想要精确的匹配，请打开`options disable-sub-class true`开关

## [#](#使用参考) 使用参考

-   模糊搜索
    
    ```
    $ sc demo.*
    demo.MathGame
    Affect(row-cnt:1) cost in 55 ms.
    ```
    
-   打印类的详细信息
    
    ```
    $ sc -d demo.MathGame
    class-info        demo.MathGame
    code-source       /private/tmp/math-game.jar
    name              demo.MathGame
    isInterface       false
    isAnnotation      false
    isEnum            false
    isAnonymousClass  false
    isArray           false
    isLocalClass      false
    isMemberClass     false
    isPrimitive       false
    isSynthetic       false
    simple-name       MathGame
    modifier          public
    annotation
    interfaces
    super-class       +-java.lang.Object
    class-loader      +-sun.misc.Launcher$AppClassLoader@3d4eac69
                        +-sun.misc.Launcher$ExtClassLoader@66350f69
    classLoaderHash   3d4eac69
    
    Affect(row-cnt:1) cost in 875 ms.
    ```
    
-   打印出类的 Field 信息
    
    ```
    $ sc -d -f demo.MathGame
    class-info        demo.MathGame
    code-source       /private/tmp/math-game.jar
    name              demo.MathGame
    isInterface       false
    isAnnotation      false
    isEnum            false
    isAnonymousClass  false
    isArray           false
    isLocalClass      false
    isMemberClass     false
    isPrimitive       false
    isSynthetic       false
    simple-name       MathGame
    modifier          public
    annotation
    interfaces
    super-class       +-java.lang.Object
    class-loader      +-sun.misc.Launcher$AppClassLoader@3d4eac69
                        +-sun.misc.Launcher$ExtClassLoader@66350f69
    classLoaderHash   3d4eac69
    fields            modifierprivate,static
                      type    java.util.Random
                      name    random
                      value   java.util.Random@522b4
                              08a
    
                      modifierprivate
                      type    int
                      name    illegalArgumentCount
    
    
    Affect(row-cnt:1) cost in 19 ms.
    ```
    
-   通过 ClassLoader#toString 查找类（前提：有一个 toString()返回值是`apo`的类加载器，加载的类中包含`demo.MathGame`, `demo.MyBar`, `demo.MyFoo`3 个类）
    
    ```
    $ sc -cs apo *demo*
    demo.MathGame
    demo.MyBar
    demo.MyFoo
    Affect(row-cnt:3) cost in 56 ms.
    ```
    

[在 GitHub 上编辑此页在新窗口打开](https://github.com/alibaba/arthas/edit/master/site/docs/doc/sc.md)

Last Updated:

贡献者: hengyunabc, Hollow Man, Hollow Man

[retransform](/doc/retransform.html) [session](/doc/session.html)