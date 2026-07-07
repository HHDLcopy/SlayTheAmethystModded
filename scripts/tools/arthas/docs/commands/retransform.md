       retransform | arthas  

[arthasv4.3.1](/)

[首页](/)

[在线教程在新窗口打开](/doc/arthas-tutorials.html?language=cn&id=arthas-basics)

[文档](/doc/)

[命令列表](/doc/commands.md)

[下载](/doc/download.md)

版本版本

-   [v3.x在新窗口打开](https://arthas.aliyun.com/3.x/)

-   [简体中文](/doc/retransform.html)
-   [English](/en/doc/retransform.html)

[](https://github.com/alibaba/arthas)

[首页](/)

[在线教程在新窗口打开](/doc/arthas-tutorials.html?language=cn&id=arthas-basics)

[文档](/doc/)

[命令列表](/doc/commands.md)

[下载](/doc/download.md)

版本版本

-   [v3.x在新窗口打开](https://arthas.aliyun.com/3.x/)

-   [简体中文](/doc/retransform.html)
-   [English](/en/doc/retransform.html)

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

# [#](#retransform) retransform

[`mc-retransform`在线教程在新窗口打开](https://arthas.aliyun.com/doc/arthas-tutorials?language=cn&id=command-mc-retransform)

提示

加载外部的`.class`文件，retransform jvm 已加载的类。

参考：[Instrumentation#retransformClasses在新窗口打开](https://docs.oracle.com/javase/8/docs/api/java/lang/instrument/Instrumentation.html#retransformClasses-java.lang.Class...-)

## [#](#使用参考) 使用参考

```
   retransform /tmp/Test.class
   retransform -l
   retransform -d 1                    # delete retransform entry
   retransform --deleteAll             # delete all retransform entries
   retransform --classPattern demo.*   # triger retransform classes
   retransform -c 327a647b /tmp/Test.class /tmp/Test\$Inner.class
   retransform --classLoaderClass 'sun.misc.Launcher$AppClassLoader' /tmp/Test.class
```

## [#](#retransform-指定的-class-文件) retransform 指定的 .class 文件

```
$ retransform /tmp/MathGame.class
retransform success, size: 1, classes:
demo.MathGame
```

加载指定的 .class 文件，然后解析出 class name，再 retransform jvm 中已加载的对应的类。每加载一个 `.class` 文件，则会记录一个 retransform entry.

提示

如果多次执行 retransform 加载同一个 class 文件，则会有多条 retransform entry.

## [#](#查看-retransform-entry) 查看 retransform entry

```
$ retransform -l
Id              ClassName       TransformCount  LoaderHash      LoaderClassName
1               demo.MathGame   1               null            null
```

-   TransformCount 统计在 ClassFileTransformer#transform 函数里尝试返回 entry 对应的 .class 文件的次数，但并不表明 transform 一定成功。

## [#](#删除指定-retransform-entry) 删除指定 retransform entry

需要指定 id：

```
retransform -d 1
```

## [#](#删除所有-retransform-entry) 删除所有 retransform entry

```
retransform --deleteAll
```

## [#](#显式触发-retransform) 显式触发 retransform

```
$ retransform --classPattern demo.MathGame
retransform success, size: 1, classes:
demo.MathGame
```

> 注意：对于同一个类，当存在多个 retransform entry 时，如果显式触发 retransform ，则最后添加的 entry 生效(id 最大的)。

## [#](#消除-retransform-的影响) 消除 retransform 的影响

如果对某个类执行 retransform 之后，想消除影响，则需要：

-   删除这个类对应的 retransform entry
-   重新触发 retransform

提示

如果不清除掉所有的 retransform entry，并重新触发 retransform ，则 arthas stop 时，retransform 过的类仍然生效。

## [#](#结合-jad-mc-命令使用) 结合 jad/mc 命令使用

```
jad --source-only com.example.demo.arthas.user.UserController > /tmp/UserController.java

mc /tmp/UserController.java -d /tmp

retransform /tmp/com/example/demo/arthas/user/UserController.class
```

-   jad 命令反编译，然后可以用其它编译器，比如 vim 来修改源码
-   mc 命令来内存编译修改过的代码
-   用 retransform 命令加载新的字节码

## [#](#上传-class-文件到服务器的技巧) 上传 .class 文件到服务器的技巧

使用`mc`命令来编译`jad`的反编译的代码有可能失败。可以在本地修改代码，编译好后再上传到服务器上。有的服务器不允许直接上传文件，可以使用`base64`命令来绕过。

1.  在本地先转换`.class`文件为 base64，再保存为 result.txt
    
    ```
    base64 < Test.class > result.txt
    ```
    
2.  到服务器上，新建并编辑`result.txt`，复制本地的内容，粘贴再保存
    
3.  把服务器上的 `result.txt`还原为`.class`
    
    ```
    base64 -d < result.txt > Test.class
    ```
    
4.  用 md5 命令计算哈希值，校验是否一致
    

## [#](#retransform-的限制) retransform 的限制

-   不允许新增加 field/method
-   正在跑的函数，没有退出不能生效，比如下面新增加的`System.out.println`，只有`run()`函数里的会生效

```
public class MathGame {
    public static void main(String[] args) throws InterruptedException {
        MathGame game = new MathGame();
        while (true) {
            game.run();
            TimeUnit.SECONDS.sleep(1);
            // 这个不生效，因为代码一直跑在 while里
            System.out.println("in loop");
        }
    }

    public void run() throws InterruptedException {
        // 这个生效，因为run()函数每次都可以完整结束
        System.out.println("call run()");
        try {
            int number = random.nextInt();
            List<Integer> primeFactors = primeFactors(number);
            print(number, primeFactors);

        } catch (Exception e) {
            System.out.println(String.format("illegalArgumentCount:%3d, ", illegalArgumentCount) + e.getMessage());
        }
    }
}
```

[在 GitHub 上编辑此页在新窗口打开](https://github.com/alibaba/arthas/edit/master/site/docs/doc/retransform.md)

Last Updated:

贡献者: hengyunabc

[reset](/doc/reset.html) [sc](/doc/sc.html)