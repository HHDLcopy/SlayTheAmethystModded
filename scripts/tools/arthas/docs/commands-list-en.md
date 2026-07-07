# All Commands (Arthas Official Docs)

## JVM Related
- dashboard     - dashboard for the system's real-time data
- getstatic     - examine class's static properties
- heapdump      - dump java heap in hprof binary format, like jmap
- jvm           - show JVM information
- logger        - print the logger information, update the logger level
- mbean         - show Mbean information
- memory        - show JVM memory information
- ognl          - execute ognl expression
- perfcounter   - show JVM Perf Counter information
- sysenv        - view system environment variables
- sysprop       - view/modify system properties
- thread        - show java thread information
- vmoption      - view/modify the vm diagnostic options
- vmtool        - jvm tool, getInstances in jvm, forceGc

## Class/Classloader Related
- classloader           - check inheritance structure, urls, class loading info
- classloader-metaspace - show metaspace/class metadata memory by ClassLoader instance
- dump                  - dump loaded classes in byte code to specified location
- jad                   - decompile specified loaded classes
- mc                    - Memory compiler, compile .java files into .class files in memory
- redefine              - load external .class files and re-define into JVM
- retransform           - load external .class files and retransform into JVM
- sc                    - check info for classes loaded by JVM
- sm                    - check methods info for loaded classes

## Monitor/Watch/Trace Related (byte-code injection)
- monitor  - monitor method execution statistics
- line     - watch arguments, local variables, and expression results at specified source lines
- stack    - display the stack trace for the specified class and method
- trace    - trace the execution time of specified method invocation
- tt       - time tunnel, record arguments/returned values and replay
- watch    - display input/output parameter, return object, and thrown exception

## Profiler/Flame Graph
- profiler - use async-profiler to generate flame graph
- jfr      - dynamic opening and closing of jfr recordings

## Authentication
- auth - authentication

## Options
- options - check/set Arthas global options

## Pipe
- grep      - filter the result with given keyword
- plaintext - remove ANSI color
- wc        - count lines

## Async Jobs
- jobs - list all jobs
- kill - forcibly terminate job
- fg   - bring suspend job to foreground
- bg   - put job to run in background

## Basic Arthas Commands
- base64  - Encode and decode using Base64 representation
- cat     - Concatenate and print files
- cls     - clear the screen
- echo    - write arguments to standard output
- grep    - Pattern searcher
- help    - display Arthas help
- history - view command history
- keymap  - keymap for Arthas keyboard shortcut
- pwd     - Return working directory name
- quit    - exit current Arthas session
- reset   - reset all enhanced classes
- session - display current session information
- stop    - terminate Arthas server
- tee     - Copies stdin to stdout, making copy in zero or more files
- version - print Arthas version attached to current Java process
