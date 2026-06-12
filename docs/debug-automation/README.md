# Debug Automation and Harness Guide

This repository exposes device automation through two layers:

1. Gradle adb tasks owned by `:app`.
2. A harness wrapper at `scripts/sts-harness.ps1` that records machine-readable results and artifacts.

The harness is the preferred entrypoint for repeatable local debugging, CI smoke checks, and Codex-driven device work. It uses the existing Gradle tasks for launcher start/stop/log export instead of bypassing the app's launch chain.

## Contract

Harness commands must write `result.json` under the selected output directory. The JSON schema version is `1` and includes:

- `success`: boolean command result.
- `status`: command-specific status or observed runtime state.
- `message`: human-readable summary.
- `applicationId`: Gradle `application.id`, normally `io.stamethyst`.
- `deviceSerial`: resolved adb serial.
- `launchMode`: requested launch mode.
- `artifacts`: output paths such as `resultJson`, `logsZip`, `screenshot`, and `debugApk`.
- `statusSnapshot`: for `doctor`, `status`, and `smoke`; includes `observedState`, optional `runtimeSignalState`, process pids, runtime storage root, boot bridge event summary, latest log tail summary, and package version.
- `operations`: every native command invoked, with exit code, timestamps, duration, command line, and output tail.

Observed runtime states are intentionally limited to signals the project actually emits:

- `READY`: `boot_bridge_events.log` contains a terminal `READY` event and the `:game` process is still visible.
- `FAIL`: `boot_bridge_events.log` contains a terminal `FAIL` event.
- `CRASH_MARKER`: `latest.log` contains a known runtime crash marker.
- `RUNNING_WITHOUT_TERMINAL_EVENT`: the `:game` process is alive but no terminal boot event has been observed.
- `LAUNCHER_RUNNING`: the launcher process is alive but no game process or terminal event is visible.
- `NOT_RUNNING`: no tracked launcher/game process is visible and no terminal event was found.
- `ERROR`: harness execution failed before a valid state could be produced.

The boot bridge event format is:

```text
TYPE<TAB>PROGRESS<TAB>MESSAGE
```

`READY` and `FAIL` are terminal. The harness does not claim that the game reached a specific menu unless the boot bridge reported `READY`.

## Harness Commands

Windows:

```powershell
.\scripts\sts-harness.ps1 -Command doctor
.\scripts\sts-harness.ps1 -Command install
.\scripts\sts-harness.ps1 -Command start -LaunchMode mts_basemod
.\scripts\sts-harness.ps1 -Command status
.\scripts\sts-harness.ps1 -Command screenshot
.\scripts\sts-harness.ps1 -Command logs
.\scripts\sts-harness.ps1 -Command stop
.\scripts\sts-harness.ps1 -Command smoke -LaunchMode mts_basemod -TimeoutSeconds 120
```

PowerShell 7 on macOS/Linux:

```bash
pwsh ./scripts/sts-harness.ps1 -Command doctor
pwsh ./scripts/sts-harness.ps1 -Command smoke -LaunchMode mts_basemod -TimeoutSeconds 120
```

Common options:

- `-DeviceSerial <adb-serial>`: required when more than one device is online.
- `-OutDir <path>`: output directory for `result.json` and artifacts. Defaults to `debug-artifacts/harness/<command>-<timestamp>`.
- `-LaunchMode mts_basemod|mts|vanilla`: defaults to `mts_basemod`.
- `-TimeoutSeconds <seconds>`: smoke/status wait timeout, default `120`.
- `-ForceJvmCrash`: expects a boot bridge `FAIL` during `smoke`.
- `-ForceRuntimeCrash`: expects a runtime crash marker during `smoke`.
- `-SkipInstall`: skip APK build/install during `smoke`.
- `-NoStopAfterSmoke`: leave the app running after `smoke`.

## Gradle Harness Tasks

Gradle wrapper tasks call the same harness script:

Windows:

```powershell
.\gradlew.bat :app:stsHarnessDoctor
.\gradlew.bat :app:stsHarnessInstall
.\gradlew.bat :app:stsHarnessStart
.\gradlew.bat :app:stsHarnessStatus
.\gradlew.bat :app:stsHarnessScreenshot
.\gradlew.bat :app:stsHarnessLogs
.\gradlew.bat :app:stsHarnessStop
.\gradlew.bat :app:stsHarnessSmoke
```

macOS/Linux:

```bash
./gradlew :app:stsHarnessDoctor
./gradlew :app:stsHarnessSmoke
```

Gradle properties:

- `-PdeviceSerial=<adb-serial>`
- `-PlaunchMode=mts_basemod|mts|vanilla`
- `-PharnessOutDir=<path>`
- `-PharnessTimeoutSeconds=<seconds>`
- `-PharnessPollIntervalSeconds=<seconds>`
- `-PharnessSkipInstall=true`
- `-PforceJvmCrash=true`
- `-PforceRuntimeCrash=true`
- `-PnoStopAfterSmoke=true`

Example:

```powershell
.\gradlew.bat :app:stsHarnessSmoke -PdeviceSerial=emulator-5554 -PlaunchMode=vanilla -PharnessOutDir=debug-artifacts\harness\vanilla-smoke
```

## Low-Level Gradle Tasks

The original adb-backed tasks remain available and are used by the harness:

Unix/macOS:

```bash
./gradlew :app:stsStart
./gradlew :app:stsStop
./gradlew :app:stsPullLogs
```

Windows:

```powershell
.\gradlew.bat :app:stsStart
.\gradlew.bat :app:stsStop
.\gradlew.bat :app:stsPullLogs
```

Options:

- `-PlaunchMode=mts_basemod`, `-PlaunchMode=mts`, or `-PlaunchMode=vanilla`.
- `-PdeviceSerial=<adb-serial>`.
- `-PlogsDir=<path>`.
- `-PforceJvmCrash=true`.
- `-PforceRuntimeCrash=true`.

`stsStart` sends `io.stamethyst.debug_launch_mode` to `LauncherActivity`. The launcher then follows the normal route through `MainScreenViewModel`, `StsGameActivity`, launch preparation, and `JvmLaunchController`.

## `stsPullLogs` Output

`stsPullLogs` writes one zip bundle named `sts-jvm-logs-export-<timestamp>.zip`.

The task resolves the same runtime root as the app:

1. external app files under `Android/data/<package>/files/sts` when readable;
2. legacy internal `files/sts` through `run-as` as fallback.

Bundle contents:

- `sts/jvm_logs/device_info.txt`
- `sts/jvm_logs/latest.log` when present
- `sts/jvm_logs/boot_bridge_events.log` when present
- `sts/jvm_logs/jvm_gc.log` when present
- `sts/jvm_logs/jvm_heap_snapshot.txt` when present
- `sts/jvm_logs/last_signal_dump.txt` when present
- up to 4 archived `sts/jvm_logs/jvm_log_*.log` files, or up to 5 if `latest.log` is absent
- memory diagnostics logs under `sts/jvm_logs/`
- up to 6 histogram files under `sts/jvm_histograms/`
- `sts/jvm_histograms/summary.txt`
- `sts/logcat/*.log*` when present
- `sts/launcher_crash_reports/sts-launcher-crash-*.txt` when present
- `sts/README.txt` when no diagnostic logs are found

## Prerequisites

- Android SDK configured through `local.properties` `sdk.dir`, `ANDROID_SDK_ROOT`, `ANDROID_HOME`, or PATH.
- At least one online adb device or emulator.
- Build dependencies required by the app, including `desktop-1.0.jar` and `runtime-pack/jre8-pojav.zip`, before running install/smoke.
- PowerShell for the harness script. Windows PowerShell works on Windows; use PowerShell 7 (`pwsh`) on macOS/Linux.
