# 命令列表 (Arthas 官方文档)

## JVM 相关
- dashboard       - 当前系统的实时数据面板
- getstatic       - 查看类的静态属性
- heapdump        - dump java heap, 类似 jmap 命令的 heap dump 功能
- jvm             - 查看当前 JVM 的信息
- logger          - 查看和修改 logger
- mbean           - 查看 Mbean 的信息
- memory          - 查看 JVM 的内存信息
- ognl            - 执行 ognl 表达式
- perfcounter     - 查看当前 JVM 的 Perf Counter 信息
- sysenv          - 查看 JVM 的环境变量
- sysprop         - 查看和修改 JVM 的系统属性
- thread          - 查看当前 JVM 的线程堆栈信息
- vmoption        - 查看和修改 JVM 里诊断相关的 option
- vmtool          - 从 jvm 里查询对象，执行 forceGc

## class/classloader 相关
- classloader             - 查看 classloader 的继承树，urls，类加载信息
- classloader-metaspace   - 按 ClassLoader 实例统计 metaspace / class metadata 内存
- dump                    - dump 已加载类的 byte code 到特定目录
- jad                     - 反编译指定已加载类的源码
- mc                      - 内存编译器，内存编译 .java 文件为 .class 文件
- redefine                - 加载外部的 .class 文件，redefine 到 JVM 里
- retransform             - 加载外部的 .class 文件，retransform 到 JVM 里
- sc                      - 查看 JVM 已加载的类信息
- sm                      - 查看已加载类的方法信息

## monitor/watch/trace 相关（字节码增强）
- monitor  - 方法执行监控
- line     - 观察指定源码行的入参、局部变量和表达式结果
- stack    - 输出当前方法被调用的调用路径
- trace    - 方法内部调用路径，并输出方法路径上的每个节点上耗时
- tt       - 方法执行数据的时空隧道，记录下指定方法每次调用的入参和返回信息
- watch    - 方法执行数据观测

## profiler/火焰图
- profiler - 使用 async-profiler 对应用采样，生成火焰图
- jfr      - 动态开启关闭 JFR 记录

## 鉴权
- auth - 鉴权

## options
- options - 查看或设置 Arthas 全局开关

## 管道
- grep      - 搜索满足条件的结果
- plaintext - 将命令的结果去除 ANSI 颜色
- wc        - 按行统计输出结果

## 后台异步任务
- jobs - 列出所有 job
- kill - 强制终止任务
- fg   - 将暂停的任务拉到前台执行
- bg   - 将暂停的任务放到后台执行

## 基础命令
- base64  - base64 编码转换
- cat     - 打印文件内容
- cls     - 清空当前屏幕区域
- echo    - 打印参数
- grep    - 匹配查找
- help    - 查看命令帮助信息
- history - 打印命令历史
- keymap  - Arthas 快捷键列表及自定义快捷键
- pwd     - 返回当前的工作目录
- quit    - 退出当前 Arthas 客户端
- reset   - 重置增强类
- session - 查看当前会话的信息
- stop    - 关闭 Arthas 服务端
- tee     - 复制标准输入到标准输出和指定的文件
- version - 输出当前目标 Java 进程所加载的 Arthas 版本号
