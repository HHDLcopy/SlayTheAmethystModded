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

Common harness options:

- `-DeviceSerial <adb-serial>`: required when more than one device is online.
- `-OutDir <path>`: output directory for `result.json` and artifacts. Defaults to `debug-artifacts/harness/<command>-<timestamp>`.
- `-LaunchMode mts_basemod|mts|vanilla`: defaults to `mts_basemod`.
- `-TimeoutSeconds <seconds>` and `-PollIntervalSeconds <seconds>`: runtime observation controls.
- `-Autoplay`: enable the bundled autoplay driver for MTS smoke runs.
- `-ForceJvmCrash` and `-ForceRuntimeCrash`: smoke expectations for crash-path validation.
- `-SkipInstall`: skip APK build/install during `smoke`.
- `-NoStopAfterSmoke`: leave the app running after `smoke`.

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
```

Harness output is always written to `result.json`. The `mods` and `set-mods` commands add `deviceMods`; `set-mods` also adds `modSelection`.

Implementation files:

- `scripts/tools/main.py`: thin tools entrypoint.
- `scripts/tools/lib/sts_harness_cli.py`: parses harness arguments.
- `scripts/tools/lib/sts_harness.py`: implements Android device selection, Gradle/adb calls, log capture, screenshots, status detection, smoke runs, and `result.json` output.
- `scripts/tools/lib/device_mods.py`: lists device mods, resolves requested optional mod tokens, and writes `enabled_mods.txt`.
