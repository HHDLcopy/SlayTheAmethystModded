# Scripts

This directory contains repository automation entrypoints. New automation must use Python for repo-owned logic, keep implementation code under the entrypoint's `lib/` directory, and update this README in the same change when a script command or harness feature is added or changed.

PowerShell script support has been removed from `scripts/`. Use the Python entrypoints below, or the small `prepare-release.bat` / `prepare-release.sh` compatibility wrappers where they are still needed by external workflows.

## Build Entrypoint

Use `scripts/build/main.py` for build, release, and packaging tasks.

Commands:

- `debug`: build a debug APK. Option: `--application-id` / `-ApplicationId`, default `io.stamethyst.debug`.
- `release`: build the slim release APK.
- `release-fast`: alias of `release` kept for compatibility.
- `release-full`: build the full release APK.
- `fast-release`: build the fast slim release APK and skip slow cleanup by default.
- `fast-release-slim`: alias of `fast-release`.
- `fast-release-full`: build the fast full release APK and skip slow cleanup by default.
- `prepare-release`: run local release preparation checks and signing setup validation.
- `package-cloud-function`: package `cloud-function/` into a zip.

Common release options:

- `--store-file` / `-StoreFile`: signing keystore path.
- `--key-alias` / `-KeyAlias`: signing key alias, default `upload`.
- `--skip-lint-check` / `-SkipLintCheck`: available on non-fast release commands.
- `--run-lint-check` / `-RunLintCheck`: available on fast release commands.
- `--skip-local-check` / `-SkipLocalCheck`: available on `prepare-release`.
- `--source-dir` / `-SourceDir` and `--output-zip` / `-OutputZip`: available on `package-cloud-function`.

Examples:

```bash
python scripts/build/main.py debug
python scripts/build/main.py release
python scripts/build/main.py release-full
python scripts/build/main.py fast-release
python scripts/build/main.py package-cloud-function
python scripts/build/main.py prepare-release
```

Implementation files:

- `scripts/build/main.py`: thin entrypoint.
- `scripts/build/lib/cli.py`: parses build command arguments and dispatches subcommands.
- `scripts/build/lib/commands.py`: implements Gradle builds, release preparation, and cloud function packaging.

## Tools Entrypoint

Use `scripts/tools/main.py` for debugging and device automation tools. The `harness` alias is equivalent to `sts-harness`.

Harness commands:

- `doctor`: validate adb, Gradle wrapper, package id, device selection, storage access, and runtime status signals.
- `install`: build and install the debug APK.
- `start`: start the app through `:app:stsStart`.
- `stop`: force-stop the app through `:app:stsStop`.
- `logs`: export runtime logs and a harness logcat dump.
- `screenshot`: capture a device screenshot.
- `status`: capture current process, boot bridge, crash marker, package, and storage state.
- `mods`: list device required mods, optional mods in `sts/mods_library`, legacy runtime mods in `sts/mods`, the current `enabled_mods.txt`, and the current `.mts_mod_file_list`.
- `set-mods`: replace the enabled optional mod selection by writing `enabled_mods.txt`.
- `smoke`: install when needed, clear runtime signals, start, wait for an expected state, capture screenshot/logs, and stop unless requested otherwise.
- `startup-cache-profile`: run one cache-build launch and then one or more cache-hit launches, exporting per-run logs and a startup timing summary.

Common harness options:

- `-DeviceSerial <adb-serial>`: required when more than one device is online.
- `-OutDir <path>`: output directory for `result.json` and artifacts. Defaults to `debug-artifacts/harness/<command>-<timestamp>`.
- `-LaunchMode mts_basemod|mts|vanilla`: defaults to `mts_basemod`.
- `-TimeoutSeconds <seconds>` and `-PollIntervalSeconds <seconds>`: runtime observation controls.
- `-Autoplay`: enable the bundled autoplay driver for MTS smoke runs.
- `-AutoplaySaveMode fresh|continue`: autoplay save handling. `fresh` clears stale saves and starts a new run; `continue` keeps saves and resumes the previous run when available.
- `-AutoplayMode normal|single_room`: selects normal long-run autoplay or a one-room combat test.
- `-DisableCardObtainEffectOwnershipCompat`: disables the bundled `ShowCardAndAddToHandEffect` ownership compatibility patch for repro runs.
- `-SingleRoomCharacter <id>` / `-SingleRoomMonster <id>` / `-SingleRoomCards <ids>`: configure the `single-room` command. Card ids are comma- or newline-separated and may include modded cards.
- `-SingleRoomSpec <path>`: local UTF-8 properties file with `character=`, `monster=`, and `cards=` for single-room tests. Put ad hoc spec files under `agent-tmp/`.
- `-ForceJvmCrash` and `-ForceRuntimeCrash`: smoke expectations for crash-path validation.
- `-SkipInstall`: skip APK build/install during `smoke`.
- `-NoStopAfterSmoke`: leave the app running after `smoke`.
- `-CacheHitRuns <count>`: for `startup-cache-profile`, number of cache-hit launches after the cache-build launch. Defaults to `1`.
- `-NoClearStartupCache`: for `startup-cache-profile`, reuse the existing startup cache instead of clearing it before the first run.

Mod selection options for `set-mods`:

- `-Mods <tokens>`: comma- or newline-separated optional mod ids, jar names, display names, launch ids, or storage paths. Repeat the option to add more tokens.
- `-ModListFile <path>`: local UTF-8 text file with one token per line. Blank lines and `#` comments are ignored.
- `-EnableAllMods`: enable every optional mod currently found in `sts/mods_library`.
- `-DisableAllMods`: disable every optional mod.

`set-mods` controls optional mods only. Required mods such as BaseMod, StSLib, Amethyst Runtime Compat, Amethyst Floating Tools, and Ram Saver are controlled by app runtime/settings behavior, not by `enabled_mods.txt`.

Examples:

```bash
python scripts/tools/main.py sts-harness -Command doctor
python scripts/tools/main.py sts-harness -Command status
python scripts/tools/main.py sts-harness -Command mods
python scripts/tools/main.py sts-harness -Command set-mods -Mods "Downfall.jar,ReplayTheSpire"
python scripts/tools/main.py sts-harness -Command set-mods -ModListFile agent-tmp/enabled-mods.txt
python scripts/tools/main.py sts-harness -Command set-mods -EnableAllMods
python scripts/tools/main.py sts-harness -Command set-mods -DisableAllMods
python scripts/tools/main.py sts-harness -Command smoke -LaunchMode mts_basemod -TimeoutSeconds 120
python scripts/tools/main.py sts-harness -Command smoke -Autoplay
python scripts/tools/main.py sts-harness -Command smoke -Autoplay -AutoplaySaveMode continue
python scripts/tools/main.py sts-harness -Command single-room -SingleRoomCharacter IRONCLAD -SingleRoomMonster Cultist -SingleRoomCards "Strike_R,Defend_R,Bash"
python scripts/tools/main.py sts-harness -Command startup-cache-profile -LaunchMode mts_basemod -CacheHitRuns 2 -SkipInstall
python scripts/tools/main.py sts-harness -Command smoke -Autoplay -DisableCardObtainEffectOwnershipCompat
```

Harness output is always written to `result.json`. The `mods` and `set-mods` commands add `deviceMods`; `set-mods` also adds `modSelection`. Autoplay now also logs and auto-resolves `CardRewardScreen` discovery/card reward pages. `single-room` writes the pushed spec to `artifacts.singleRoomSpec`, waits for a `[amethyst-autoplay] single_room result ...` line in `latest.log`, stores it at `statusSnapshot.latestLog.singleRoomResult`, exports logs, and then stops the app.
`startup-cache-profile` writes a top-level `startupCacheProfile` summary and a `startup-cache-profile-summary.json` artifact. Each phase also gets its own subdirectory with `result.json`, logs, logcat, cache state before/after, detected cache mode, and extracted timing evidence from `latest.log`.

## Architecture

The tools directory is organized into modular components, each with single
responsibility:

| Module | Role |
|--------|------|
| `connector/` | 常驻守护进程，设备连接管理、TCP 透传代理、adb 命令代理 |
| `lib/agent_client.py` | game-probe 协议客户端（通过 connector.connect_stream 通信） |
| `lib/env_device.py` | 从 `STS_TEST_DEVICE` 环境变量读取设备序列号，所有测试和脚本共用 |
| `arthas/` | Arthas JVM 诊断工具集成（通过 connector + arthas-bridge） |
| `harness/` | 高层编排（build, install, smoke, single-room, startup-cache） |
| `autoplay/` | 游戏自动化控制 |
| `monitor/` | 日志采集、截图、文件拉取 |

各模块通过 `connector` daemon 访问设备，**不直接调用 adb**，**不直接开 TCP 连接**。
详见各模块目录下的 `README.md`。

## 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `STS_TEST_DEVICE` | 集成测试和自动化脚本的默认设备序列号。`scripts/tools/lib/env_device.py` 的 `get_test_device_serial()` 读取该变量，各测试文件和 `HarnessOrchestrator` 通过该函数统一获取设备序列。设为 `"auto"` 时由 connector daemon 自动选择 `adb devices` 中的第一个设备。 | `auto` |

```bash
export STS_TEST_DEVICE=localhost:15555
```

```
harness / arthas / autoplay / monitor
        │
        ▼
   ┌─────────────┐
   │  connector  │  ← Unix socket ~/.sts/connector.sock (daemon)
   └──────┬──────┘
          │ adb
          ▼
    Android Device
    ├── game-probe (:9099)   ← OBSERVE / EXEC / LOAD_AGENT
    └── arthas-bridge    (:8099)  ← ArthasBootstrapCompat (无 Netty)
```

Implementation files:

- `scripts/tools/main.py`: thin tools entrypoint.
- `scripts/tools/connector/`: 设备通信守护进程及其客户端库。
- `scripts/tools/lib/agent_client.py`: 统一 game-probe TCP 协议客户端。
- `scripts/tools/lib/sts_harness_cli.py`: 遗留 harness CLI 解析器（将被 harness/ 模块替代）。
- `scripts/tools/lib/sts_harness.py`: 遗留 harness 实现（将被 harness/ 模块替代）。
- `scripts/tools/lib/device_mods.py`: 列出设备 mod、解析可选 mod token、写入 `enabled_mods.txt`。
