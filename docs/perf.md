<br>
## 结论
进入游戏后确有若干明显的性能与能耗问题。核心矛盾是：**大量已经写好的性能优化开关默认全部关闭，而大量诊断/轮询设施默认全部开启**。

> **状态说明（本轮复核于当前工作树逐条重新验证，行号已更新）**
> 每条问题标题后带状态标记：
> - `[已修复]` — 代码已改，问题不再存在
> - `[待办]` — 复核确认仍然存在
> - `[已失效]` — 前提条件已变，原结论不再成立
---
## 一、帧率与卡顿
### 1. 默认配置下同时没有 vsync 对齐，又用忙等定时器限帧 `[已修复]`
原始问题链路（供对照）：
- `LwjglGraphics.setVSync()` 无论传入什么都强制 `vsync=false`，除非 `amethyst.lwjgl.egl_swap_interval_pacing=true`；该开关默认 `false`。
- `shouldUseSoftwareSync()` 在 `!graphics.vsync` 时直接返回 `true`，所以每一帧结尾都会走软件限帧。
- 软件限帧走 `Display.sync(frameRate)`，尾段 busy-spin；更好的 `syncAndroidFramePacer` 藏在默认 `false` 的开关后面。

**当前状态**：已按"删除开关、保留最优路径"处理。`syncSoftwareFrame()`（`LwjglApplication.java:665-683`）无条件走 sleep/park 到帧截止时间，`sleepUntilFrameDeadline()`（`:685-706`）分 sleep / `parkNanos` / 直接返回三段，唯一调用点在主循环尾部（`:2183`）。`Display.sync()`、`shouldUseSoftwareSync()`、vsync margin 逻辑均已删除。`LwjglGraphics.setVSync()` 硬编码 false，`JREUtils` 里 `FORCE_VSYNC=false`/`LIBGL_VSYNC=0`。代码注释记录了实测：90Hz 面板 90 FPS 目标下进程 CPU -13%、渲染线程 CPU -14%，呈现帧率与抖动不变。

同时删除的四个开关（`egl_swap_interval_pacing`、`hot_loop_noop_trim`、`default_fbo_fast_rebind`、`native_pre_swap_pacing`）一律按 OFF 语义固化，涉及 `LauncherConfig`、`CompatibilitySettings`、`StsLaunchSpec` 的 `-D` 参数与托管键列表、`JvmLaunchController` 审计键、兼容性/设置两处 UI 与 ViewModel、三个语言的 `strings.xml`，以及 `gl_bridge.c` 里约 76 行原生 pre-swap pacer。
### 2. 每帧约 9 次 `System.getProperty` `[待办]`
> 注意：原文说"打开 `lwjgl_hot_loop_noop_trim` 即可"已不适用。该开关连同它的 `*_AT_START` 静态镜像在上一轮被**删除**，所有调用点回到直接读属性。也就是说这一条不但没修，可选的现成修法也没了，需要重新实现属性提升。

主循环每帧实际执行的属性读取：

| 调用 | 位置 | 每帧次数 | 读取方式 |
|---|---|---|---|
| `shouldLogGpuResourceSummary()` | `:2063` → `:2203-2206` | 1（内含 2 次读取） | `readBooleanSystemProperty` ×2 |
| `runGlobalTextureCompatOnManagedGrowth()` → `shouldEnableGlobalTextureCompat()` | `:2074`、`:2102` → `:1694`/`:1726` → `:1310-1313` | 2 | `readBooleanSystemProperty` |
| `shouldRunGlobalTextureCompatScan()` | `:2075` → `:1569-1577` | 1 | `readBooleanSystemProperty` |
| `resolvePhysicalDisplayWidth/Height()` | `:2082-2083` → `:531-541` | 2 | `readPositiveIntProperty`（`:555`） |
| `shouldForceDefaultFramebuffer()` | `:2148` → `:1156-1167` | 1 | `System.getProperty` |
| `shouldPostRenderClear()` | `:2149` → `:1169-1171` | 1 | `Boolean.getBoolean` |

每次都是 `Properties`（`Hashtable`）的 synchronized 查表。单次不贵，乘以 90 FPS 是纯浪费。原文列的"FBO rebind 缓存开关（`:1168`）"已随 `default_fbo_fast_rebind` 一起删除，不再计入。

一个实现约束：`shouldForceDefaultFramebuffer()` 在属性缺省时回落到 `LwjglGraphics.isGLESContextActive()`（运行时状态），不能整体提为 `static final`；只能缓存属性解析结果，保留上下文查询。
### 3. 纹理绑定路径上的无条件计时 `[待办]`
`GLTexture.bind()`（`GLTexture.java:375-376`）与 `bind(int)`（`:382-383`）都调 `notifyBeforeTextureAccess()`（`:924-932`），其中 `lastAccessTimeNanos = nowMonotonicNanos()`（`:930`），而 `nowMonotonicNanos()` 就是 `System.nanoTime()`（`:2212-2214`）。这条路径**没有任何开关保护**，另有 4 处 setter 入口（`:426`、`:438`、`:451`、`:464`）与 `getHandle`（`:407`）也走同一函数。

唯一消费 `lastAccessTimeNanos` 的是 `getIdleDurationNanos()`（`:1134-1137`），只在常驻回收判定里用（`:1122`），而 `TEXTURE_RESIDENCY_MANAGER_ENABLED` 默认 `false`（`:169-170`）。即默认配置下这个时间戳**写了但永远没人读**。

`SpriteBatch` 侧同理：`switchTexture()` 的 `FRAME_TEXTURE_SWITCHES.incrementAndGet()`（`SpriteBatch.java:1238`）和 `flush()` 的 `FRAME_FLUSHES.incrementAndGet()`（`:1141`）都无条件执行，而读取方 `consumeFrameDiagnostics()`（`:86-90`）只有一个调用点，在 `LwjglApplication.java:2174` 的 `if (frameSample != null)` 分支内——即仅当帧分析器开启时。StS 每帧数千次 `switchTexture`，所以这是每帧数千次 `nanoTime()` 加数千次原子自增，全部服务于默认关闭的功能。
### 4. JIT 被限制在 C1 `[待办]`
`-XX:TieredStopAtLevel=2`（常量 `StsLaunchSpec.kt:43`，添加于 `:145`）意味着热点代码永远进不了 C2。对 StS 这种长时间运行的游戏，稳态性能会明显低于完整分层编译。同时 `-XX:ActiveProcessorCount=3`（常量 `:42`，添加于 `:166-168`）在多核设备上限制了 GC 与 JIT 编译线程的并行度。
这两条也许是为稳定性刻意选择的，但代价没有在代码注释里说明——相邻的 `-Xint` 回退（`:139-142`）和压缩指针（`:149-150`）都有注释解释原因，这两条没有。
---
## 二、电量与温度
### 1. 屏幕永不变暗（默认） `[待办]`
`KEEP_SCREEN_ON_TIMEOUT_ALWAYS_MINUTES = 0`、`DEFAULT_KEEP_SCREEN_ON_TIMEOUT_MINUTES = KEEP_SCREEN_ON_TIMEOUT_ALWAYS_MINUTES`（`LauncherConfig.kt:240-241`），`0` 在 `keepScreenOnTimeoutMs()`（`:1105-1107`）映射为 `null`；`null` 时 `StsGameActivity.kt:537` 的 `if (timeoutMs != null && !bootOverlayKeepScreenOn)` 不成立，空闲 runnable 从不投递，`updateKeepScreenOnFlag()`（`:543-549`）因 `keepScreenOnActive` 恒为 true 而全程 `addFlags(FLAG_KEEP_SCREEN_ON)`。手机上屏幕通常是最大耗电项，也是面板/PMIC 发热主源。
顺带一个小问题：`resetKeepScreenOnIdleTimer()` 在 `onTouchEvent`、`dispatchTouchEvent`、`onGenericMotionEvent`、`dispatchKeyEvent` 四处都被调用（`StsGameActivity.kt:607`、`:612`、`:617`、`:622`），而 `dispatchTouchEvent` 与 `onTouchEvent` 对同一手势都会触发，所以每个 `ACTION_MOVE` 会执行两次 `removeCallbacks` + `postDelayed`。当前默认（always）下 `postDelayed` 不会发生，只有重复的 `removeCallbacks`；一旦用户选了有限超时，双次投递就实际发生。
### 2. 启动器 logcat 抓取默认开启，且整局游戏都在跑 `[已修复]`
> 路径更正：这些文件在 `io/stamethyst/backend/diag/`（不是 `backend/diagnostics/`）。

原始问题：`DEFAULT_LAUNCHER_LOGCAT_CAPTURE_ENABLED = true`（`LauncherConfig.kt:303`），在 `onResume` 经 `syncLauncherLogcatCapture()` 启动，游戏启动路径只操作另一个 `LogcatCaptureProcessClient`（游戏进程抓取），没有任何地方在开局时停掉启动器抓取。worker 循环每 250 ms 做一次 `ActivityManager.getRunningAppProcesses()` 全量枚举，并常驻一个 `logcat` 子进程持续写滚动文件。空闲停止守卫失效：`trackedProcessMatcher` 的匹配条件是 `processName == packageName`，而启动器主进程一直存活，所以 `stopWhenNoTrackedProcessesIdleMs` 的计数永不归零。净效果是每秒 4 次 binder 全进程枚举 + 常驻 logcat 管道 + 持续闪存写入，整局有效。

**当前状态**：两处独立修复。

其一，开局停止。新增 `LauncherActivity.stopLauncherLogcatCaptureForGameLaunch()`（`LauncherActivity.kt:418-438`），两条启动路径都调用：`MainScreenViewModel.kt:3539-3544`（常规启动）与 `LauncherActivity.kt:294`（直连调试启动）。用 `stopCapture` 而非 `stopAndClear`，已落盘的启动器日志保留给反馈包。游戏会话自己的 `LogcatCaptureProcessClient` 用 `isTrackedPackageProcessName`（匹配 `pkg` 与 `pkg:*`），整局仍然覆盖启动器进程，没有采集空档。另加 `restoreLauncherLogcatCaptureAfterFailedGameLaunch()`（`:440-446`）用于启动失败路径——那种情况下启动器从未被 pause，`onResume` 不会触发。

其二，轮询降频。`TRACKED_PROCESS_REFRESH_INTERVAL_MS` 从 `PackageLogcatCaptureWorker` 的 companion 常量下移为 `PackageLogcatCaptureConfig` 字段（`PackageLogcatCaptureWorker.kt:22-32`），默认值仍为 250 ms 以保持游戏抓取行为不变；`LauncherLogcatCaptureService.kt:14-20` 覆盖为 2 s。启动器抓取只跟踪一个长生命周期进程，快速子 PID 发现对它没有价值。

两项合计：从整局每秒 4 次 binder 枚举，降到仅启动器前台时每秒 0.5 次。

`processName == packageName` 匹配条件**保持原样**——放宽它会让启动器抓取与游戏抓取范围重叠，停止抓取是更直接的修法。
### 3. 隐藏的 EasyTier 浮层每秒一次 binder + 磁盘 `[已修复]`
原始问题：`InGameEasyTierOverlayController.kt` 的 1 秒轮询 `LaunchedEffect` 位于可见性提前返回（`if (!visible || kickDialog != null) return`）**之前**。`ComposeView` 从创建起就以 `visibility = View.GONE` 挂在窗口上，GONE 但 attached 的 `ComposeView` 仍会组合，所以这个循环整局运行。每次 `viewModel.syncEasyTierUi(activity)` 会读 EasyTier 状态文件，并在本地未标记运行时调用 `ActivityManager.getRunningServices(Int.MAX_VALUE)`。

**当前状态**：没有按原建议"移到可见性判断之后"，而是**整个轮询循环删除**（`InGameEasyTierOverlayController.kt:143-151`），只留一次 `syncEasyTierUi` 调用；`EASY_TIER_KICK_STATE_POLL_INTERVAL_MS` 常量一并删除。

理由是数据流复核显示轮询根本不必要：`EasyTierProcessService.broadcastSnapshot`（`:152-168`）已经把每次状态变化作为 `ACTION_CONNECTION_EVENT` 广播推出，踢出路径也在内（`handleTerminalSessionState` `:789-835` → `deliverSnapshot` `:195-209`）。ViewModel 侧 `ensureEasyTierProcessEventReceiverRegistered` 注册了接收器，`handleEasyTierProcessEvent` → `publishEasyTierIndicator` → `maybeQueueEasyTierKickDialog` 负责弹窗。保留的那一次调用同时承担两件事：注册接收器，以及覆盖"踢出发生在浮层尚未组合时"的冷启动场景。

净效果：整局每秒一次状态文件读取加一次 `getRunningServices(Int.MAX_VALUE)` binder 调用被完全消除。旁边 5 秒的房间刷新（`:192-201` 附近）原本就正确地放在可见性判断之后，未改动。
### 4. `:game` 进程主线程每秒约 38 次文件系统调用 `[待办]`
从 `onRuntimeReady` 启动（`GameSessionCoordinator.kt:195-198`），仅在 `onDestroy` 停止（`:243-246`）；`onPause` 不停，后台也跑：

| 轮询 | 间隔 | 常量位置 |
|---|---|---|
| 键盘请求 | 120 ms | `GameSessionCoordinator.kt:48` |
| 文件选择器请求 | 120 ms | `:50` |
| 救援 toast 请求 | 120 ms | `:51` |
| LAN 状态 | 300 ms | `:49` |
| 预期退出看门狗 | 100 ms | `ExpectedGameExitReturnPolicy.kt:48` |

每次都是 `File.isFile` + `readText`。主 looper 永不空闲，阻止 CPU 进入深度 idle。
### 5. 一个读取空文件的 1 秒线程 `[待办]`
`STS-RuntimeHeap` 每 1 秒（`RUNTIME_HEAP_SNAPSHOT_POLL_INTERVAL_MS = 1_000L`，`JvmLaunchController.kt:67`）读 `jvm_heap_snapshot.txt`（线程体 `:551-570`），启动点在 `measureStartupStep("start_monitors")` 内、**无条件**（`:254`）。但该文件只在性能浮层开启时才会被写入——`-Damethyst.bridge.heap_snapshot=...` 只在 `if (showPerformanceOverlay)` 分支添加（`StsLaunchSpec.kt:707-708`）。浮层默认关闭 → **整局纯空转唤醒，1 次/秒**。
`STS-LatestLogcat` 2 秒一次（`LATEST_LOG_LOGCAT_POLL_INTERVAL_MS = 2_000L`，`:62`；线程体 `:510-532`）tail `latest.log`，同样无条件启动（`:252`），即使 `mirrorJvmLogsToLogcat` 为 false 也照样读取扫描——该标志只在逐行输出处检查（`:815`、`:1101`）。
### 6. 陀螺仪无条件以 `SENSOR_DELAY_GAME` 注册 `[待办]`
`StsGameActivity.kt:304-307`，约 50 Hz，没有检查是否真有功能消费陀螺仪数据。每个事件经 `onSensorChanged`（`:261-273`）转发到 `forwardGyroscope`，穿一次 JNI。前台作用域是正确的（`onPause` 走 `unregisterGyroscope`，`:326-332`），但开销无条件存在。
### 7. 其他 `[待办]`
- Presence 心跳按 `CloudControlConfig.current().heartbeatIntervalMs` 重投（`GamePresenceReporter.kt:99-106`）。**复核确认**：该类只有 `start()`（`:74`），没有 `stop()`；`running`（`:49`）除声明处的初始值外没有任何 `false` 赋值，`:78` 置 true 后永不清除。启动器进程在游戏期间存活（`onPause` 只 `finish()` Activity，不杀进程），所以整局保持 `wss://` 长连接。
- Workshop 更新检查 10 分钟一次，进程级 scope，从不取消。
- EasyTier 前台服务在联机时每 5 秒一次 HTTPS + 用户态 VPN 转发，代码注释明确说明要活过 Activity 销毁。仅联机时存在，但那时是很强的耗电与发热项。
---
## 三、内存
### 1. 512 MB 固定堆，不随设备内存缩放 `[待办]`
`DEFAULT_JVM_HEAP_MAX_MB = 512`（`LauncherConfig.kt:331`），区间 `MIN=256`/`MAX=2048`/`STEP=128`（`:332-334`）。整条 sizing 路径（`normalizeJvmHeapMaxMb` `:1145-1149`、`readJvmHeapMaxMb` `:1157-1160`）没有任何 `getMemoryClass()` / `totalMem` / `/proc/meminfo` 读取——16 GB 和 4 GB 手机都拿 `-Xmx512M`。

顺带一个发现：`resolveDefaultGpuResourceGuardianMode(totalMemoryBytes: Long)`（`:1586-1588`）带 `@Suppress("UNUSED_PARAMETER")`，直接返回常量。说明"按设备内存自适应"的接口形状曾经存在过，后来被压平成固定值，参数留着没用。

而且 `resolveJvmHeapStartMb` 把起始堆 clamp 到 `DEFAULT_JVM_HEAP_MAX_MB`（`:1152-1155`），所以**默认设置下 `Xms == Xmx == 512M`**，`StsLaunchSpec.kt:160-162` 那段"保守起始堆"注释仅在用户手动调高滑块时才成立。
启动器自己知道 512 不够：`JvmLaunchController.kt:857-871` 的 `buildHeapPressureNotice()` 在峰值占用超过 `HEAP_PRESSURE_WARNING_RATIO` 后建议 +`JVM_HEAP_STEP_MB`。那是事后提示，不是自适应。
### 2. `-XX:+DisableExplicitGC` 让 ram-saver 模组的 GC 策略完全失效 `[待办]`
`StsLaunchSpec.kt:169` 无条件添加 `-XX:+DisableExplicitGC`。
而 `mods/ram-saver` 的 `AggressiveGC.java:51` 在 BaseMod 生命周期钩子里调 `System.gc()` — 全部变成 no-op。ram-saver 整个设计基于弱引用 + `ReferenceQueue`，它的释放路径只在收集器真正清除并入队弱引用后才运行，抑制显式 GC 会延迟清除，进而延迟释放**原生**纹理内存。
`ramSaverEnabled` 在 `StsLaunchSpec.kt:126` 已经算出来了（`isMtsLaunchMode(launchMode) && ModManager.isRamSaverEnabled(context)`），并且已经用于其他多处决策（`:540`、`:557`、`:586`、`:624`，以及 `:806-824` 三个 resolver）。这里应该把 `DisableExplicitGC` 改为条件添加。注意 `DEFAULT_RAM_SAVER_ENABLED = true`（`LauncherConfig.kt:296` 附近），即默认冲突就存在。
### 3. 内存预算不自洽，且回收机制默认全关 `[待办]`
- Java 堆 512 MB
- 纹理常驻软预算 768 MB，硬下限 512 MB（`GLTexture.java:177-181`：`Math.max(512MB, readLongSystemProperty(..., 768MB, ...))`）
- 加上 metaspace、code cache、GL 驱动分配

设计点超过 1.2 GB，全在一个 Android 进程内。而三项回收机制默认全部关闭：`DEFAULT_LARGE_TEXTURE_DOWNSCALE_COMPAT_ENABLED = false`（`LauncherConfig.kt:355`）、`DEFAULT_TEXTURE_RESIDENCY_MANAGER_COMPAT_ENABLED = false`（`:356`）、`DEFAULT_GPU_RESOURCE_GUARDIAN_MODE = OFF`（`:360`）。每帧调用的 `GLTexture.reclaimIdleTextures`（`LwjglApplication.java:2133`）在 `GLTexture.java:823` 因 `!TEXTURE_RESIDENCY_MANAGER_ENABLED` 直接返回。
这个形状在 6 GB 设备上更容易表现为 lmkd 中途杀进程，而不是 Java `OutOfMemoryError`。
### 4. `Hitbox.registeredHitboxes` 整局只增不减 `[已失效 / 部分待办]`
> **原结论的前提不成立。** 开关默认值是 **true**，不是 false。

事实核对：
- `registeredHitboxes` 确实每个构造函数都加一个 `WeakReference`（`Hitbox.java:27`、`:47`、`:107-112`）。
- 清理只发生在 `refreshAllHoveredForFreshClick()`（`:50-67`，`:60-61` 移除已失效引用），该方法在 `:51` 因 `!isPreClickHoverRefreshEnabled()` 提前返回。
- 但 `isPreClickHoverRefreshEnabled()`（`:125-132`）在属性缺失时**默认 `Boolean.TRUE`**，而属性来自 `-Damethyst.pre_click_hitbox_hover_refresh_enabled`（`StsLaunchSpec.kt:323-324`），由 `readCompendiumUpgradeTouchFixEnabled` 驱动，其默认值 `DEFAULT_COMPENDIUM_UPGRADE_TOUCH_FIX_ENABLED = true`（`LauncherConfig.kt:291`）。

所以**默认配置下修剪是开启的**，"永不修剪的无界增长"这一条不成立。只有用户主动关闭该触控修复时才退化成泄漏。

**仍然成立的是第二个代价**：修剪是对整个列表的 O(n) 反向扫描，且发生在输入处理路径内部（经 `deferFreshPreClickHoverClick` `:89-96` / `dispatchDeferredPreClickHoverInput` `:74-87`）。长时间对局后期，每次点击都要扫一遍数万条大部分已失效的条目。这条按"输入延迟"而非"内存泄漏"处理更准确。
### 5. `getMethod` 反射未缓存 `[待办]`
`LwjglApplication.java:1633-1642` 的 `textureDataUseMipMaps` 每次调 `Texture.class.getMethod("getTextureData")`（`:1636`），而 `Class.getMethod` 每次返回新的 `Method` 副本。该方法在 `:1758` 对每个候选纹理每次扫描都调用，未缓存。同一文件 `:1274` 的诊断路径也是同样写法。
同一个逻辑 `SpriteBatch.java:271-279` 用直接类型化调用实现（`texture.getTextureData()` + `textureData.useMipMaps()`），无反射——修复范式在代码库里已经存在。
---
## 排查结果为阴性的部分
为免重复排查，这些确认是干净的：
- `app/src/main/` 内**不存在** WakeLock / WifiLock（grep `newWakeLock`、`PARTIAL_WAKE_LOCK`、`WifiLock` 共 0 命中，本轮已重新验证）
- `GLTexture` 里的静态 `ConcurrentHashMap` 看着可怕，但 handle-keyed 的在 `releaseHandle` 时清理，聚合类的键词汇表有限——确认了移除路径，不是泄漏
- `SpriteBatch.flush()` 与各 `draw` 重载无分配、无字符串拼接
- logcat 抓取与日志 tail 都写滚动文件/定长窗口，无会话级字符串累积
- `:game` 进程无静态 Activity/Context 持有
- 性能浮层采样器（1 秒 Handler 循环、`/proc/self/status` 线程、GC 直方图）全部正确门控且默认关闭
- 模组侧多数缓存有配对的 add/remove（`TouchscreenCardInputRuntime`、`LazyCustomCardImagePatches`、`CompatRuntimeState` 等）
- `GpuLeakInjector.LEAKED_TEXTURES` 是真泄漏但是刻意的测试装置，且属性只在 debug 构建可注入——`addDebugGpuGuardianTestProperties`（`StsLaunchSpec.kt:879-892`）在 `BuildConfig.BUILD_TYPE != "debug"` 时直接返回空结果，`amethyst.gdx.debug_leak_injector` 在 `DEBUG_GPU_GUARDIAN_PROPERTY_KEYS`（`:50-54`）内

**本轮补充验证的两项原"未验证"条目**（结论均为阴性/无需担心）：
- `CompatibilitySettings.isRuntimeTextureCompatEnabled` 委托 `LauncherConfig.isRuntimeTextureCompatEnabled`（`CompatibilitySettings.kt:209-211`），后者 `getBoolean(PREF_KEY_RUNTIME_TEXTURE_COMPAT, false)`（`LauncherConfig.kt:1325`）→ **默认关闭**。因此 `shouldEnableGlobalTextureCompat()`（`LwjglApplication.java:1310-1313`）中的 `enabled` 为 false，每帧两次纹理兼容检查会在第一个条件就短路，不会真正扫描纹理。属性读取本身的开销仍计入第 2 条。
- `GameSessionCoordinator.foregroundAudioRestoreRunnables`（`:92`）移除路径**完整**：`scheduleForegroundAudioRestoreRetries()`（`:892-904`）第一行就调 `cancelForegroundAudioRestoreRetries()`，后者（`:906-914`）遍历 `removeCallbacks` 后 `clear()`；另有 6 处调用点（`:250`、`:281`、`:289`、`:872`、`:893`、`:1186`）覆盖失焦、后台、销毁路径。不是泄漏。
---
## 建议的处理顺序
按预期收益排序。已完成项移到下方"已完成"小节，保留供对照。

1. **给纹理绑定路径的无条件计时加门控**（第一部分第 3 条）。`GLTexture.notifyBeforeTextureAccess` 里的 `nanoTime()`（`GLTexture.java:930`）仅在 `TEXTURE_RESIDENCY_MANAGER_ENABLED` 时才需要 `lastAccessTimeNanos`；同时给 `SpriteBatch` 的 `FRAME_FLUSHES`（`SpriteBatch.java:1141`）/ `FRAME_TEXTURE_SWITCHES`（`:1238`）自增加同样门控，其唯一读取点在帧分析器分支内。**这是剩余项里量级最大的一条**——每帧数千次，全部服务于默认关闭的功能。
2. **重新实现主循环属性提升**（第一部分第 2 条）。原先的 `lwjgl_hot_loop_noop_trim` 开关已被删除，需要直接把属性解析结果缓存为静态字段。注意 `shouldForceDefaultFramebuffer()` 必须保留 `isGLESContextActive()` 的运行时查询，不能整体提为 `static final`。每帧 9 次 `Hashtable` synchronized 查表，无行为风险。
3. **`-XX:+DisableExplicitGC` 改为条件化**：`StsLaunchSpec.kt:169` 加 `if (!ramSaverEnabled)`。`ramSaverEnabled` 已在 `:126` 算好，且 ram-saver 默认开启（`LauncherConfig.kt:296`），所以默认配置下这个冲突现在就在发生——ram-saver 的原生纹理释放路径被拖慢。改动面小、收益明确。
4. **让 `STS-RuntimeHeap` 只在性能浮层开启时启动**（`JvmLaunchController.kt:254` 加条件，与 `StsLaunchSpec.kt:707-708` 的写入条件对齐），`STS-LatestLogcat` 同理按 `mirrorJvmLogsToLogcat` 门控（`:252`）。前者默认配置下是纯空转唤醒，1 次/秒读一个永远不存在的文件。
5. **`-Xmx` 按设备内存缩放**，在 `LauncherConfig.kt:1157` 的 `readJvmHeapMaxMb` 无存储值时读一次 `MemoryInfo.totalMem`，clamp 在现有 256–2048 区间内。可以顺手复用 `resolveDefaultGpuResourceGuardianMode(totalMemoryBytes)`（`:1586`）那个已存在但被废弃的签名形状。
6. **摊销 `Hitbox.registeredHitboxes` 的 O(n) 扫描**。注意这一条已从"内存泄漏"改判为"输入延迟"：默认配置下修剪是开启的，问题是每次点击都要 O(n) 扫描。可在 `beginPreClickHoverRefreshFrame()`（`Hitbox.java:69-72`）里按帧计数分摊压缩，把点击路径的扫描降为增量。
7. **缓存 `textureDataUseMipMaps` 的反射**（`LwjglApplication.java:1636`、`:1274`），或改为 `SpriteBatch.java:271-279` 那样的直接类型化调用。非每帧路径，收益有限。
8. 复核 `-XX:TieredStopAtLevel=2`（`StsLaunchSpec.kt:43`）与 `-XX:ActiveProcessorCount=3`（`:42`）是否仍有必要，若是稳定性妥协请在代码里注明原因。**这条需要设备实测才能定，不适合纯代码改动。**
9. `:game` 进程主线程的 5 个高频文件轮询（第二部分第 4 条，合计约 38 次/秒）。可以考虑合并为单个 tick 或改为文件观察者，但这是接口重构而非局部修复，改动面明显大于前面各项。
10. 屏幕常亮默认值（第二部分第 1 条）属于产品决策而非 bug，若要改需要产品侧同意；顺手可以把 `dispatchTouchEvent`/`onTouchEvent` 的重复 `resetKeepScreenOnIdleTimer()` 去掉一处。
11. 陀螺仪注册（第二部分第 6 条）与 Presence 心跳缺少 stop 路径（第二部分第 7 条）。前者需要先确认是否真有功能消费数据，后者需要确认长连接是产品意图还是遗漏。两条都需要先定性再动手。

### 已完成
- 帧同步：删除开关、保留 sleep/park 最优路径（第一部分第 1 条）
- 启动器 logcat 抓取：开局停止 + 轮询 250 ms → 2 s（第二部分第 2 条）
- EasyTier 隐藏浮层：删除 1 秒轮询，改用已有广播（第二部分第 3 条）
---
两点说明：以上全部来自源码阅读，我没有在设备上运行过，所以没有实测的 mA 或核温数据；排序依据是唤醒频率、系统调用/binder 成本与屏幕/射频行为。唯一有实测数据的是第一部分第 1 条（已修复），数据来自代码注释里记录的对比。

原文末尾列的两项"未验证"已在本轮全部验证完毕，结论见上方阴性清单——两项都不构成问题，其中纹理兼容扫描的默认关闭状态还削弱了第一部分第 2 条的严重性（属性读取仍在，但短路后不扫描纹理）。