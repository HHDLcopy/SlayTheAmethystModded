# MTS startup cache strategies

This document summarizes the startup cache and cache-hit-only optimization path for
ModTheSpire launches on Amethyst. The user-facing setting is `Enable mod cache` /
`启用模组缓存`; when it is disabled, the launcher does not build or use the MTS
patch cache and the cache-hit runtime optimizations stay inactive.

## Launcher and boot-bridge caches

### MTS classpath warmup cache

Implemented by `MtsClasspathWarmupCoordinator`.

Before the JVM game process starts, the main launcher process validates the imported
`desktop-1.0.jar`, ensures runtime components are installed, resolves the enabled mod
list, patches the game body if needed, and prepares the derived MTS classpath jars.
The cache marker is keyed by the desktop jar, ModTheSpire, BaseMod, StSLib, and the
GDX patch jar. A cache hit lets the launcher skip rebuilding these derived classpath
artifacts during later starts.

This warmup deliberately runs in the main process before launching the game process.
That keeps the old `:prep` process out of the launch path and lets the main process
release its preparation resources once the game process is running.

### MTS patch output cache

Implemented by `MtsPatchCacheCoordinator`, `MtsLoaderCrashPatcher`,
`MtsPatchCacheStore`, and `MtsPatchCacheBootstrap`.

On a cache miss, the patched ModTheSpire loader still runs the normal patching flow.
During that flow, Amethyst temporarily enables the MTS out-jar/package path, captures
the patched main game jar, writes modded package jars, merges compiled base-game patch
classes back into the cached jar, and then writes a marker file after all cache files
are valid.

On a cache hit, `MtsPatchCacheBootstrap.launchIfCurrent()` bypasses the normal MTS
patching flow and directly invokes the cached `PackageJar.PrepackagedLauncher` with:

- `desktop-1.0-modded.jar`
- the cached per-mod `*-modded.jar` package directory
- the original base jar as a fallback classpath entry

The cache key includes the desktop jar, ModTheSpire, BaseMod, StSLib, boot bridge,
GDX patch jar, the MTS mod file list content, and every enabled mod jar's path,
length, and last-modified timestamp.

### Cached MTS annotation database

Implemented by `MtsPatchAnnotationDbCache`.

The cache-miss patching flow serializes MTS's `Patcher.annotationDBMap` after patch
discovery. On cache-hit launch, the prepackaged launcher restores the annotation DB
for the cached mod package URLs instead of rescanning every mod jar for Spire patch
annotations.

If the annotation DB cache is missing or incomplete, boot bridge falls back to MTS's
original `Patcher.findPatches(...)` scan.

### Cached main-jar SpireEnum index

Implemented by `MtsPatchMainJarSpireEnumCache`.

MTS still needs `@SpireEnum` entries from the patched main jar when launching through
the prepackaged cache. The cache-miss path scans the patched main jar once and stores
the holder class names. The cache-hit path reads that small text index and applies
enum busting without rescanning the large cached jar.

If the index is missing or invalid, boot bridge falls back to scanning the patched
jar for `@SpireEnum` annotations.

## Runtime cache-hit optimizations

These patches live in `mods/amethyst-runtime-compat` and are gated by
`amethyst.mts.patch_cache.current=true` unless noted otherwise. They are intentionally
inactive for non-cache launches.

### Loadout class scan cache

Implemented by `LoadoutClassScanCachePatches`.

Loadout scans every enabled mod jar to discover optional card modifiers, powers,
orbs, and monsters. Amethyst replaces those repeated `ClassFinder` scans with a
persistent per-mod cache under the MTS patch cache directory. Each entry stores class
names for one kind of Loadout lookup and is keyed by the MTS patch cache marker plus
the mod id/version when available.

On a cache miss, one jar scan builds all four Loadout indexes. On later launches,
Loadout reloads the cached class names, revalidates them through class loading and
type checks, and fills Loadout's original maps synchronously. This removes the
startup fan-out of many Loadout scanner threads. The patch stays inactive whenever
`amethyst.mts.patch_cache.current=false`, so non-cache and cache-miss launches keep
Loadout's original threaded scanner behavior.

Disable with `amethyst.runtime_compat.loadout_class_scan_cache=false`.

### Downfall ClassFinder scan cache

Implemented by `ClassFinderScanCachePatches`.

Downfall registers several character modules during `BaseMod.publishEditCards()`.
Each module creates a new `ClassFinder` and scans the same `Downfall.jar` to find
its card classes. Amethyst instruments those Downfall `autoAddCards()` call sites so
the first scan builds an in-memory `ClassInfo` list, and later modules reapply their
own original `ClassFilter` against that shared list.

This preserves each module's filter, ordering, and `ClassFinder` superclass/interface
lookup behavior. The same patch also falls back to the class-resource jar URL when
`ProtectionDomain.getCodeSource()` is unavailable under the cached MTS modded-jar
loader.

Disable with `amethyst.runtime_compat.class_finder_scan_cache=false`. Optional
profiling: `amethyst.runtime_compat.class_finder_scan_cache_profile=true`.

### Lazy custom card images

Implemented by `LazyCustomCardImagePatches`.

During cache-hit `BaseMod.publishEditCards()`, many `CustomCard` instances decode
their portrait images even though no card art is visible before the main menu. This
patch records the requested image path and skips the immediate `loadCardImage(...)`.
The real image is loaded before the card is first rendered in normal card render
paths.

Disable with `amethyst.runtime_compat.lazy_custom_card_images=false`. Optional
profiling: `amethyst.runtime_compat.lazy_custom_card_images_profile=true`.

### Lazy startup card descriptions

Implemented by `LazyStartupCardDescriptionPatches`.

During cache-hit card registration, thousands of startup-only card prototypes call
`AbstractCard.initializeDescription()`. This patch defers description parsing while
`BaseMod.publishEditCards()` is running, then initializes the description before the
card is rendered, previewed, copied, checked for `canUse`, or has upgrade text
displayed.

Disable with `amethyst.runtime_compat.lazy_startup_card_descriptions=false`.
Optional profiling: `amethyst.runtime_compat.lazy_startup_card_descriptions_profile=true`.

### Lazy card library screen

Implemented by `LazyCardLibraryScreenPatches`.

The base main menu constructs and initializes the card library screen during startup.
On cache-hit launches, Amethyst defers the startup-only `CardLibraryScreen.initialize()`
call and runs it synchronously the first time the player opens the card library.

Disable with `amethyst.runtime_compat.lazy_card_library_screen=false`.

### Fast cache splash

Implemented by `FastCacheSplashScreenPatches`.

This is not a data cache. It is a cache-hit-only startup timing optimization. Once
the game is already in the cached path, the patch moves the base splash screen
directly to a visible logo hold, writes the launcher splash event only after the logo
has rendered, accelerates the remaining fade-out, and writes the ready event after
`MainMenuScreen` is constructed.

Disable with `amethyst.runtime_compat.fast_cache_splash=false`. Tune with
`amethyst.runtime_compat.fast_cache_splash_visible_hold_seconds` and
`amethyst.runtime_compat.fast_cache_splash_fade_out_seconds`.

## Supporting non-cache optimization

`LoadoutBaseGameMonsterSkipPatches` is a cache-hit-only companion optimization. It
skips Loadout's base-game monster scan only when `amethyst.mts.patch_cache.current=true`
because the original cache-hit path spends time scanning and then fails with an empty
map in the observed Android runtime. Non-cache launches keep Loadout's original scan
so the monster selector can still populate vanilla monsters.

`BaseModEditCardsTimingPatches` and `BaseModPostInitializeTimingPatches` are also
not cache strategies. They are diagnostic wrappers used to identify which subscriber
owns the remaining startup work.

## Invalidation and fallback rules

The launcher invalidates startup caches when core runtime assets, game body patches,
MTS components, or the enabled mod set change. The patch cache marker also changes
when any enabled mod jar's path, length, or last-modified timestamp changes.

Cache reads are conservative:

- If the marker is missing or mismatched, MTS runs the normal patching flow.
- If the cached main jar is missing or too small, MTS runs the normal patching flow.
- If cached package jars are missing, MTS runs the normal patching flow.
- If annotation or enum sub-caches are missing, only that sub-cache falls back to
  the original scan.
- Runtime compat cache-hit patches check `amethyst.mts.patch_cache.current=true`,
  so they do not alter non-cache launches.

## Cache build parallelism

Each mod's package jar is an independent read-modify-write: it reads only its own
source jar and the shared immutable entry snapshot, and writes only its own target
jar. `writeFastPackageJars` therefore spreads the mods across a small fixed pool
instead of writing them one at a time.

The pool is capped at `min(availableProcessors, 4, modCount)`. The work is bound by
storage as much as by CPU, so a larger pool mostly thrashes the flash on the devices
this runs on. Override with `-Damethyst.mts.patch_cache.package_jar_threads=<n>`;
`1` forces the original serial path.

Two mods whose source jars share a file name resolve to the same target path. MTS's
serial loop let the later mod overwrite the earlier one; writing them concurrently
would instead interleave both into one file and corrupt it. Tasks are keyed by target
path so only the last mod per target is written, which preserves the original
last-wins result and removes the collision.

A worker failure is re-raised from `writeFastPackageJars`. By the time the fast path
runs it has already taken over MTS's output stream, so it must fail loudly rather than
report success over a partially written package directory — `store` then falls back to
the normal patching flow and never commits a marker.

## Write durability

The marker is the commit point for the whole cache, so it must never become durable
before the artifacts it vouches for. `MtsPatchCacheStore.store` fsyncs the main jar,
the package jars, and both sub-caches, and only then writes the marker. Without that
ordering the filesystem is free to persist the small marker ahead of the large jars,
which would turn a power loss during a cache build into a silent cache hit on
truncated data — the 1 MiB size floor only catches truncation to near-zero.

Every cache file is replaced through `AtomicFileWriter`: write to `<name>.tmp`, fsync
the contents, rename over the target, then fsync the directory entry. `renameTo` over
an existing path is atomic on the Android filesystems the launcher targets; the
delete-then-copy path is a fallback for filesystems that refuse it. This removes the
window where a crash mid-write could leave a half-written sub-cache in place.

## Class loading under a cache hit

`ChildFirstJarClassLoader` loads the cached jars child-first so the patched copies win
over the unpatched ones still on the launch classpath. Two rules keep that from
breaking type identity:

- A small set of namespaces is parent-first, because a class loaded on both sides
  produces two distinct `Class` objects and fails with `ClassCastException` as soon as
  an instance crosses the boundary: the JDK namespaces, the endorsed XML/GSS packages,
  `com.badlogic.gdx`, `org.lwjgl`, log4j/slf4j, and `io.stamethyst.bridge`. ModTheSpire's
  own classes are deliberately *not* parent-first — the cached jar carries the patched
  copies and those are the ones the game must run. Matching is on a package boundary,
  so an unrelated mod class such as `javafx.Thing` is not mistaken for a JDK class.
  Parent-first lookups fall back to the child on miss, since these namespaces are not
  guaranteed to be complete in the parent: `io.stamethyst.bridge.FirstPersonGyroBridge`
  ships inside the gdx patch merged into the cached jar and has never been on the
  launch classpath.
- Resource lookup is child-first to match class lookup. Otherwise the parent's
  unpatched `ModTheSpire.jar` can answer for a resource whose class-side counterpart
  came from the cached jar. `getResources` returns child entries before parent ones.

The loader registers as parallel-capable and locks per class name. Locking the whole
loader instead would serialize every load performed by Loadout's scanner threads,
BaseMod, and the GDX asset threads, and risks deadlock when a parent-first delegation
happens while another thread holds the parent's lock.
