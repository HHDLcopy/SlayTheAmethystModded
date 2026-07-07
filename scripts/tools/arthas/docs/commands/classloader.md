       classloader | arthas  

[arthasv4.3.1](/)

[首页](/)

[在线教程在新窗口打开](/doc/arthas-tutorials.html?language=cn&id=arthas-basics)

[文档](/doc/)

[命令列表](/doc/commands.md)

[下载](/doc/download.md)

版本版本

-   [v3.x在新窗口打开](https://arthas.aliyun.com/3.x/)

-   [简体中文](/doc/classloader.html)
-   [English](/en/doc/classloader.html)

[](https://github.com/alibaba/arthas)

[首页](/)

[在线教程在新窗口打开](/doc/arthas-tutorials.html?language=cn&id=arthas-basics)

[文档](/doc/)

[命令列表](/doc/commands.md)

[下载](/doc/download.md)

版本版本

-   [v3.x在新窗口打开](https://arthas.aliyun.com/3.x/)

-   [简体中文](/doc/classloader.html)
-   [English](/en/doc/classloader.html)

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

# [#](#classloader) classloader

[`classloader`在线教程在新窗口打开](https://arthas.aliyun.com/doc/arthas-tutorials?language=cn&id=command-classloader)

提示

查看 classloader 的继承树，urls，类加载信息

`classloader` 命令将 JVM 中所有的 classloader 的信息统计出来，并可以展示继承树，urls 等。

可以让指定的 classloader 去 getResources，打印出所有查找到的 resources 的 url。对于`ResourceNotFoundException`比较有用。

## [#](#参数说明) 参数说明

参数名称

参数说明

\[l\]

按类加载实例进行统计

\[t\]

打印所有 ClassLoader 的继承树

\[a\]

列出所有 ClassLoader 加载的类，请谨慎使用

`[c:]`

ClassLoader 的 hashcode

`[classLoaderClass:]`

指定执行表达式的 ClassLoader 的 class name

`[c: r:]`

用 ClassLoader 去查找 resource

`[c: load:]`

用 ClassLoader 去加载指定的类

### [#](#url-classes-参数说明) `--url-classes` 参数说明

参数名称

参数说明

`--url-classes`

统计指定 ClassLoader 中，已加载类与 `codeSource(URL/jar)` 的关系

`-d, --details`

详情模式：列出每个 URL/jar 加载的类名（建议配合 `-n/--limit` 控制输出量）

`--jar <kw>`

按 jar 包名/URL 关键字过滤（默认包含匹配）

`--class <kw>`

按类名/包名关键字过滤（默认包含匹配）

`-E, --regex`

`--jar/--class` 使用正则匹配（默认关键字包含匹配）

`-n, --limit <N>`

详情模式下，每个 URL/jar 最多展示 N 个类（100 默认）

## [#](#使用参考) 使用参考

### [#](#按类加载类型查看统计信息) 按类加载类型查看统计信息

```
$ classloader
 name                                       numberOfInstances  loadedCountTotal
 com.taobao.arthas.agent.ArthasClassloader  1                  2115
 BootstrapClassLoader                       1                  1861
 sun.reflect.DelegatingClassLoader          5                  5
 sun.misc.Launcher$AppClassLoader           1                  4
 sun.misc.Launcher$ExtClassLoader           1                  1
Affect(row-cnt:5) cost in 3 ms.
```

### [#](#按类加载实例查看统计信息) 按类加载实例查看统计信息

```
$ classloader -l
 name                                                loadedCount  hash      parent
 BootstrapClassLoader                                1861         null      null
 com.taobao.arthas.agent.ArthasClassloader@68b31f0a  2115         68b31f0a  sun.misc.Launcher$ExtClassLoader@66350f69
 sun.misc.Launcher$AppClassLoader@3d4eac69           4            3d4eac69  sun.misc.Launcher$ExtClassLoader@66350f69
 sun.misc.Launcher$ExtClassLoader@66350f69           1            66350f69  null
Affect(row-cnt:4) cost in 2 ms.
```

### [#](#查看-classloader-的继承树) 查看 ClassLoader 的继承树

```
$ classloader -t
+-BootstrapClassLoader
+-sun.misc.Launcher$ExtClassLoader@66350f69
  +-com.taobao.arthas.agent.ArthasClassloader@68b31f0a
  +-sun.misc.Launcher$AppClassLoader@3d4eac69
Affect(row-cnt:4) cost in 3 ms.
```

### [#](#查看-urlclassloader-实际的-urls) 查看 URLClassLoader 实际的 urls

```
$ classloader -c 3d4eac69
file:/private/tmp/math-game.jar
file:/Users/hengyunabc/.arthas/lib/3.0.5/arthas/arthas-agent.jar

Affect(row-cnt:9) cost in 3 ms.
```

*注意* hashcode 是变化的，需要先查看当前的 ClassLoader 信息，提取对应 ClassLoader 的 hashcode。

对于只有唯一实例的 ClassLoader 可以通过 class name 指定，使用起来更加方便：

```
$ classloader --classLoaderClass sun.misc.Launcher$AppClassLoader
file:/private/tmp/math-game.jar
file:/Users/hengyunabc/.arthas/lib/3.0.5/arthas/arthas-agent.jar

Affect(row-cnt:9) cost in 3 ms.
```

### [#](#使用-classloader-去查找-resource) 使用 ClassLoader 去查找 resource

```
$ classloader -c 3d4eac69  -r META-INF/MANIFEST.MF
 jar:file:/System/Library/Java/Extensions/MRJToolkit.jar!/META-INF/MANIFEST.MF
 jar:file:/private/tmp/math-game.jar!/META-INF/MANIFEST.MF
 jar:file:/Users/hengyunabc/.arthas/lib/3.0.5/arthas/arthas-agent.jar!/META-INF/MANIFEST.MF
```

也可以尝试查找类的 class 文件：

```
$ classloader -c 1b6d3586 -r java/lang/String.class
 jar:file:/Library/Java/JavaVirtualMachines/jdk1.8.0_60.jdk/Contents/Home/jre/lib/rt.jar!/java/lang/String.class
```

### [#](#使用-classloader-去加载类) 使用 ClassLoader 去加载类

```
$ classloader -c 3d4eac69 --load demo.MathGame
load class success.
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
```

### [#](#统计-classloader-实际使用-url-和未使用的-url) 统计 ClassLoader 实际使用 URL 和未使用的 URL

注意

注意，基于 JVM 目前已加载的所有类统计，不代表`Unused URLs`可以从应用中删掉。因为可能将来需要从`Unused URLs`里加载类，或者需要加载`resources`。

```
$ classloader --url-stat
 com.taobao.arthas.agent.ArthasClassloader@3c41660, hash:3c41660
 Used URLs:
 file:/Users/admin/.arthas/lib/3.5.6/arthas/arthas-core.jar
 Unused URLs:

 sun.misc.Launcher$AppClassLoader@75b84c92, hash:75b84c92
 Used URLs:
 file:/Users/admin/code/java/arthas/math-game/target/math-game.jar
 file:/Users/admin/.arthas/lib/3.5.6/arthas/arthas-agent.jar
 Unused URLs:

 sun.misc.Launcher$ExtClassLoader@7f31245a, hash:7f31245a
 Used URLs:
 file:/tmp/jdk1.8/Contents/Home/jre/lib/ext/sunec.jar
 file:/tmp/jdk1.8/Contents/Home/jre/lib/ext/sunjce_provider.jar
 file:/tmp/jdk1.8/Contents/Home/jre/lib/ext/localedata.jar
 Unused URLs:
 file:/tmp/jdk1.8/Contents/Home/jre/lib/ext/nashorn.jar
 file:/tmp/jdk1.8/Contents/Home/jre/lib/ext/cldrdata.jar
 file:/tmp/jdk1.8/Contents/Home/jre/lib/ext/legacy8ujsse.jar
 file:/tmp/jdk1.8/Contents/Home/jre/lib/ext/jfxrt.jar
 file:/tmp/jdk1.8/Contents/Home/jre/lib/ext/dnsns.jar
 file:/tmp/jdk1.8/Contents/Home/jre/lib/ext/openjsse.jar
 file:/tmp/jdk1.8/Contents/Home/jre/lib/ext/sunpkcs11.jar
 file:/tmp/jdk1.8/Contents/Home/jre/lib/ext/jaccess.jar
 file:/tmp/jdk1.8/Contents/Home/jre/lib/ext/zipfs.jar
```

### [#](#查看指定-classloader-的类与-jar-url-的关系列表) 查看指定 ClassLoader 的类与 jar(URL) 的关系列表

`--url-classes` 用于统计指定 ClassLoader 中，类来自哪个 jar(URL)，以及每个 jar(URL) 加载了多少类。

```
$ classloader -c 3d4eac69 --url-classes
sun.misc.Launcher$AppClassLoader@3d4eac69, hash:3d4eac69
 url                                            loadedClassCount
 file:/private/tmp/math-game.jar                 42
 file:/Users/hengyunabc/.arthas/lib/arthas-agent.jar  15
Affect(row-cnt:2) cost in 3 ms.
```

按 jar 包名关键字过滤并查看详情（列出类名）：

```
$ classloader -c 3d4eac69 --url-classes -d --jar math-game
```

进一步按包名/关键字过滤（同时会输出 `matchedClassCount` 便于统计）：

```
$ classloader -c 3d4eac69 --url-classes --jar spring-core --class org.springframework
```

[在 GitHub 上编辑此页在新窗口打开](https://github.com/alibaba/arthas/edit/master/site/docs/doc/classloader.md)

Last Updated:

贡献者: hengyunabc, Hollow Man, LHearen

[cat](/doc/cat.html) [classloader-metaspace](/doc/classloader-metaspace.html)