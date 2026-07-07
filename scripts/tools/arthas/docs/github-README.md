# Arthas - GitHub README (from master)

(Content fetched from https://raw.githubusercontent.com/alibaba/arthas/master/README.md)

## Key features
- Check whether a class is loaded, or where the class is being loaded
- Decompile a class (jad)
- View classloader statistics
- View method invocation details (params, return object, exception)
- Check stack trace of specified method invocation
- Trace method invocation to find slow sub-invocations
- Monitor method invocation statistics (qps, rt, success rate)
- Monitor system metrics, thread states, CPU usage, GC stats
- Command line interactive mode with auto-complete
- Telnet and websocket support
- Profiler / Flame Graph
- Get objects in heap that are instances of specified class (vmtool)
- Supports JDK 8+ (JDK 17, 21, 25)
- Linux/Mac/Windows

## Showcase commands
- dashboard - system real-time data panel
- thread -n 3 - top CPU consuming threads
- jad - decompile class
- mc - memory compiler
- retransform - hotswap loaded classes
- sc - search loaded class with detailed info
- vmtool - get objects in heap
- stack - view call stack
- trace - find slow method invocations
- watch - watch method params/return/exception
- monitor - method invocation statistics
- tt - time tunnel, record and replay
- classloader - view classloader info
- profiler - flame graph / CPU profiling

## Documentation
- https://arthas.aliyun.com/doc/en
- Commands: https://arthas.aliyun.com/doc/en/commands.html
