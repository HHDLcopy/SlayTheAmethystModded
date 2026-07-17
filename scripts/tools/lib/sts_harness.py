import json
import os
import posixpath
import re
import shutil
import subprocess
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from scripts.tools.harness._context import HarnessContext

COMMANDS = (
    "doctor",
    "install",
    "start",
    "stop",
    "logs",
    "screenshot",
    "status",
    "mods",
    "set-mods",
    "smoke",
    "decompil",
    "agent-attach",
    "agent-detach",
    "agent-list",
    "agent-status",
    "play",
    "perf",
    "hotreload",
    "single-room",
    "startup-cache-profile",
    "console",
    "steam-cloud-sync",
)
LAUNCH_MODES = ("mts_basemod", "mts", "vanilla")
AGENT_COMMANDS = ("attach", "detach", "list", "status")
AUTOPLAY_SAVE_MODES = ("fresh", "continue")
AUTOPLAY_MODES = ("normal", "single_room")
SINGLE_ROOM_DEFAULT_REMOTE_SPEC = "autoplay-single-room.properties"
SINGLE_ROOM_RESULT_PREFIX = "[amethyst-autoplay] single_room result "
STARTUP_CACHE_EVIDENCE_PATTERNS = (
    "Launching cached MTS patch jar",
    "Patch cache miss:",
    "Writing MTS patch cache jar",
    "MTS patch cache is ready",
    "Wrote cached MTS annotation DB",
    "Wrote cached MTS main jar SpireEnum",
    "Restored cached MTS annotation DB",
    "Prepared cached MTS prepackaged launch",
    "Applied cached MTS SpireEnum entries",
    "Loaded cached MTS main jar SpireEnum entries",
    "Finished cached autoAddCardMods",
    "Finished cached autoAddStuffs",
    "MTS patch cache step",
    "ClassFinder scan cache",
    "BaseMod.publishEditCards subscriber",
    "BaseMod.postInitialize subscriber",
    "LazyCustomCardImage",
    "LazyStartupCardDescription",
)
DEFAULT_CLOUD_SYNC_RELATIVE_PATH = "saves/.amethyst-cloud-sync-harness.txt"
STEAM_CLOUD_SUCCESS_OPERATIONS = {"manual_push", "force_push"}


def repo_root() -> Path:
    return Path(__file__).resolve().parents[3]


def utc_timestamp(value: datetime | None = None) -> str:
    value = value or datetime.now(timezone.utc)
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def file_timestamp() -> str:
    return datetime.now().strftime("%Y%m%d-%H%M%S-%f")


def limit_text(text: str | None, max_length: int = 6000) -> str:
    if not text:
        return ""
    if len(text) <= max_length:
        return text
    return text[-max_length:]


def format_command_for_log(file_path: str | Path, arguments: list[str] | tuple[str, ...] = ()) -> str:
    parts = [str(file_path), *[str(argument) for argument in arguments]]
    return " ".join(f'"{part.replace(chr(34), chr(92) + chr(34))}"' if re.search(r'[\s"]', part) else part for part in parts)


def read_key_value_file(path: Path, name: str, default: str = "") -> str:
    if not path.exists():
        return default
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        if key.strip() == name:
            return value.strip()
    return default


def read_local_property(path: Path, name: str) -> str:
    value = read_key_value_file(path, name, "")
    if not value:
        return ""
    return value.replace(r"\:", ":").replace(r"\\", "\\")


def read_local_text_tail(path: Path | str, max_bytes: int = 131072) -> str:
    path = Path(path)
    if not path.exists():
        return ""
    try:
        with path.open("rb") as stream:
            stream.seek(0, os.SEEK_END)
            length = stream.tell()
            if length <= 0:
                return ""
            stream.seek(max(0, length - max_bytes), os.SEEK_SET)
            return stream.read(max_bytes).decode("utf-8", errors="replace")
    except OSError:
        return ""


def quote_android_shell(value: str) -> str:
    return "'" + value.replace("'", "'\"'\"'") + "'"


def text_contains(text: str | None, needle: str) -> bool:
    return bool(text) and needle.lower() in text.lower()


def parse_decompil_target(raw: str) -> tuple[str, str | None]:
    target = raw.strip()
    if not target:
        raise ValueError("decompil target must not be empty")
    if "#" in target:
        class_name, method_name = target.split("#", 1)
        class_name = class_name.strip()
        method_name = method_name.strip()
        if not class_name:
            raise ValueError(f"class name missing in decompil target: {target}")
        if not method_name:
            raise ValueError(f"method name missing in decompil target: {target}")
        return class_name, method_name
    return target, None


def split_csv_tokens(value: str | None) -> list[str]:
    if not value:
        return []
    tokens: list[str] = []
    for token in re.split(r"[,\r\n]+", value):
        stripped = token.strip()
        if stripped:
            tokens.append(stripped)
    return tokens


def encode_properties_value(value: str) -> str:
    return (
        value.replace("\\", "\\\\")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
    )


@dataclass
class CommandResult:
    exit_code: int
    output: str


@dataclass
class LogcatCapture:
    process: subprocess.Popen
    stdout_stream: Any
    stderr_stream: Any
    log_path: Path
    stderr_path: Path
    started_at: datetime
    command: str


@dataclass
class HarnessOptions:
    command: str
    launch_mode: str
    device_serial: str
    out_dir: str
    timeout_seconds: int
    poll_interval_seconds: int
    force_jvm_crash: bool
    force_runtime_crash: bool
    autoplay: bool
    skip_install: bool
    no_stop_after_smoke: bool
    mods: list[str]
    mod_list_file: str
    enable_all_mods: bool
    disable_all_mods: bool
    debug_mode: bool = False
    autoplay_save_mode: str = "fresh"
    autoplay_mode: str = "normal"
    single_room_spec: str = ""
    single_room_device_spec: str = ""
    single_room_character: str = ""
    single_room_monster: str = ""
    single_room_cards: str = ""
    disable_card_obtain_effect_ownership_compat: bool = False
    decompil_targets: list[str] = field(default_factory=list)
    agent_command: str = ""
    agent_spec: str = ""
    agent_port: int = 9099
    agent_duration: float = 0.0
    redefine_class_file: str = ""
    console_command: str = ""
    cache_hit_runs: int = 1
    no_clear_startup_cache: bool = False
    cloud_sync_relative_path: str = DEFAULT_CLOUD_SYNC_RELATIVE_PATH
    cloud_sync_payload: str = ""
    cloud_sync_source_file: str = ""
    cloud_sync_pull_interval_seconds: int = 10


class Harness:
    def __init__(self, options: HarnessOptions) -> None:
        self.options = options
        self.repo_root = repo_root()
        self.gradle_wrapper: Path | None = None
        self.adb_path: str | None = None
        self.application_id: str | None = None
        self.resolved_device_serial = options.device_serial.strip()
        self.operations: list[dict[str, Any]] = []
        self.started_at = datetime.now(timezone.utc)
        self.result: dict[str, Any] = {}
        self._cached_out_dir: Path | None = None

    def _build_context(self) -> HarnessContext:
        return HarnessContext(
            options=self.options,
            repo_root=self.repo_root,
            gradle_wrapper=self.gradle_wrapper,
            adb_path=self.adb_path,
            application_id=self.application_id,
            resolved_device_serial=self.resolved_device_serial,
            operations=self.operations,
            started_at=self.started_at,
            result=self.result,
            cached_out_dir=self._cached_out_dir,
        )

    def resolve_repo_path(self, path: str | Path) -> Path:
        path = Path(path)
        if path.is_absolute():
            return path.resolve()
        return (self.repo_root / path).resolve()

    def default_out_dir(self) -> Path:
        return self.repo_root / "debug-artifacts" / "harness" / f"{self.options.command}-{file_timestamp()}"

    def resolved_out_dir(self) -> Path:
        if self._cached_out_dir is None:
            if not self.options.out_dir.strip():
                self._cached_out_dir = self.default_out_dir()
            else:
                self._cached_out_dir = self.resolve_repo_path(self.options.out_dir)
        return self._cached_out_dir

    def resolve_gradle_wrapper(self) -> Path:
        windows_wrapper = self.repo_root / "gradlew.bat"
        unix_wrapper = self.repo_root / "gradlew"
        if os.name == "nt" and windows_wrapper.exists():
            return windows_wrapper
        if unix_wrapper.exists():
            return unix_wrapper
        if windows_wrapper.exists():
            return windows_wrapper
        raise RuntimeError(f"Missing Gradle wrapper under: {self.repo_root}")

    def resolve_adb_path(self) -> str:
        adb_name = "adb.exe" if os.name == "nt" else "adb"
        local_sdk = read_local_property(self.repo_root / "local.properties", "sdk.dir")
        if local_sdk and not Path(local_sdk).is_absolute():
            local_sdk = str((self.repo_root / local_sdk).resolve())
        candidates = [os.environ.get("ANDROID_SDK_ROOT", ""), os.environ.get("ANDROID_HOME", ""), local_sdk]
        for sdk in [candidate for candidate in candidates if candidate.strip()]:
            adb = Path(sdk).expanduser().resolve() / "platform-tools" / adb_name
            if adb.exists():
                return str(adb)
        adb = shutil.which("adb")
        if adb:
            return adb
        raise RuntimeError("Could not resolve adb. Set sdk.dir, ANDROID_SDK_ROOT, ANDROID_HOME, or add adb to PATH.")


    def select_device(self) -> None:
        from scripts.tools.harness._runner import run_native
        if not self.adb_path:
            raise RuntimeError("adb is not initialized.")
        result = run_native(self._build_context(), self.adb_path, ["devices"], timeout_seconds=15, allow_failure=True)
        if result.exit_code != 0:
            raise RuntimeError("adb devices failed.")
        online_devices = []
        for line in re.split(r"\r?\n", result.output):
            match = re.match(r"^([^\s]+)\s+device$", line.strip())
            if match:
                online_devices.append(match.group(1))
        if self.resolved_device_serial:
            if self.resolved_device_serial not in online_devices:
                raise RuntimeError(f"Requested device is not connected and online: {self.resolved_device_serial}")
            return
        if not online_devices:
            raise RuntimeError("No connected Android device or emulator is online.")
        if len(online_devices) > 1:
            raise RuntimeError(f"Multiple Android devices are online. Pass -DeviceSerial. Devices: {', '.join(online_devices)}")
        self.resolved_device_serial = online_devices[0]

    def initialize(self) -> None:
        self.gradle_wrapper = self.resolve_gradle_wrapper()
        self.adb_path = self.resolve_adb_path()
        self.application_id = read_key_value_file(self.repo_root / "gradle.properties", "application.id", "io.stamethyst")
        if not self.application_id.strip():
            raise RuntimeError("application.id cannot be empty.")
        if self.options.autoplay and self.options.launch_mode == "vanilla":
            raise RuntimeError("Autoplay requires -LaunchMode mts or mts_basemod because the bundled autoplay driver is loaded as an MTS mod.")
        if self.options.command == "single-room":
            if self.options.launch_mode == "vanilla":
                raise RuntimeError("single-room requires -LaunchMode mts or mts_basemod because it is implemented by the bundled MTS autoplay mod.")
            self.options.autoplay = True
            self.options.autoplay_mode = "single_room"
        self.select_device()

    def gradle_device_properties(self) -> list[str]:
        if self.resolved_device_serial:
            return [f"-PdeviceSerial={self.resolved_device_serial}"]
        return []

    def effective_debug_launch_mode(self) -> str:
        return "mts" if self.options.launch_mode == "mts_basemod" else self.options.launch_mode

    def harness_install(self) -> None:
        self.gradle([":app:assembleDebug"])
        apk_root = self.repo_root / "app" / "build" / "outputs" / "apk" / "debug"
        apks = sorted(apk_root.glob("*.apk"), key=lambda item: item.stat().st_mtime, reverse=True) if apk_root.exists() else []
        if not apks:
            raise RuntimeError(f"No debug APK found under: {apk_root}")
        apk = apks[0]
        self.result["artifacts"]["debugApk"] = str(apk)
        self.adb(["install", "-r", str(apk)], timeout_seconds=180)

    def harness_start(self) -> None:
        launcher_component = f"{self.application_id or ''}/.LauncherActivity"
        if self.options.command == "steam-cloud-sync":
            self.adb(
                ["shell", "am", "start", "-n", launcher_component],
                timeout_seconds=30,
            )
            return
        single_room_device_spec = ""
        if self.options.autoplay_mode == "single_room":
            single_room_device_spec = self.ensure_single_room_device_spec()
        args = [
            "shell",
            "am",
            "start",
            "-n",
            launcher_component,
            "--es",
            "io.stamethyst.debug_launch_mode",
            self.effective_debug_launch_mode(),
            "--ez",
            "io.stamethyst.debug_force_jvm_crash",
            str(self.options.force_jvm_crash).lower(),
            "--ez",
            "io.stamethyst.debug_force_runtime_crash",
            str(self.options.force_runtime_crash).lower(),
            "--ez",
            "io.stamethyst.debug_autoplay",
            str(self.options.autoplay).lower(),
            "--es",
            "io.stamethyst.debug_autoplay_save_mode",
            self.options.autoplay_save_mode,
            "--es",
            "io.stamethyst.debug_autoplay_mode",
            self.options.autoplay_mode,
        ]
        if single_room_device_spec:
            args.extend(
                [
                    "--es",
                    "io.stamethyst.debug_autoplay_single_room_spec",
                    single_room_device_spec,
                ]
            )
        args.extend(
            [
                "--ez",
                "io.stamethyst.debug_disable_card_obtain_effect_ownership_compat",
                str(self.options.disable_card_obtain_effect_ownership_compat).lower(),
            ]
        )
        self.adb(args, timeout_seconds=30)

    def harness_stop(self) -> None:
        self.adb(["shell", "am", "force-stop", self.application_id or ""], timeout_seconds=20)

    def harness_logs(self, output_directory: Path) -> None:
        output_directory.mkdir(parents=True, exist_ok=True)
        try:
            self.gradle([":app:stsPullLogs", f"-PlogsDir={output_directory}", *self.gradle_device_properties()])
        except Exception as exc:
            self.result["artifacts"]["logsGradleError"] = str(exc)
            self.harness_logs_via_adb(output_directory)
            return
        archives = sorted(output_directory.glob("sts-jvm-logs-export-*.zip"), key=lambda item: item.stat().st_mtime, reverse=True)
        if archives:
            self.result["artifacts"]["logsZip"] = str(archives[0])

    def harness_logs_via_adb(self, output_directory: Path) -> Path:
        sts_root = self.resolve_device_sts_root()
        storage_root = self.resolve_device_storage_root(sts_root)
        fallback_dir = output_directory / "logs-fallback"
        fallback_dir.mkdir(parents=True, exist_ok=True)
        files = {
            "lastOperationSummary": self.collect_remote_text_snapshot(
                storage_root,
                "steam-cloud/last-operation-summary.txt",
                fallback_dir / "steam-cloud" / "last-operation-summary.txt",
                parser=self.parse_steam_cloud_summary,
            ),
            "pushSummary": self.collect_remote_text_snapshot(
                storage_root,
                "steam-cloud/push-summary.txt",
                fallback_dir / "steam-cloud" / "push-summary.txt",
                parser=self.parse_steam_cloud_push_summary,
            ),
            "pullSummary": self.collect_remote_text_snapshot(
                storage_root,
                "steam-cloud/pull-summary.txt",
                fallback_dir / "steam-cloud" / "pull-summary.txt",
            ),
            "manifest": self.collect_remote_text_snapshot(
                storage_root,
                "steam-cloud/manifest.json",
                fallback_dir / "steam-cloud" / "manifest.json",
            ),
            "baseline": self.collect_remote_text_snapshot(
                storage_root,
                "steam-cloud/sync-baseline.json",
                fallback_dir / "steam-cloud" / "sync-baseline.json",
            ),
            "latestLog": self.collect_remote_text_snapshot(
                sts_root,
                "latest.log",
                fallback_dir / "sts" / "latest.log",
                tail_lines=400,
            ),
            "bootBridgeEvents": self.collect_remote_text_snapshot(
                sts_root,
                "boot_bridge_events.log",
                fallback_dir / "sts" / "boot_bridge_events.log",
            ),
        }
        summary = {
            "collectedAt": utc_timestamp(),
            "storageRoots": {"sts": sts_root, "storage": storage_root},
            "files": files,
        }
        summary_path = fallback_dir / "summary.json"
        summary_path.write_text(
            json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        self.result["artifacts"]["logsFallbackSummary"] = str(summary_path)
        return summary_path

    def harness_screenshot(self, output_directory: Path) -> Path:
        output_directory.mkdir(parents=True, exist_ok=True)
        timestamp = file_timestamp()
        remote_path = f"/sdcard/sts_harness_{timestamp}.png"
        local_path = output_directory / f"sts-screen-{timestamp}.png"
        self.adb(["shell", "screencap", "-p", remote_path])
        try:
            self.adb(["pull", remote_path, str(local_path)], timeout_seconds=60)
        finally:
            self.adb(["shell", "rm", remote_path], allow_failure=True)
        if not local_path.exists() or local_path.stat().st_size <= 0:
            raise RuntimeError(f"Screenshot was not created or is empty: {local_path}")
        self.result["artifacts"]["screenshot"] = str(local_path)
        return local_path

    def clear_runtime_signals(self) -> None:
        sts_root = self.resolve_device_sts_root()
        for relative_path in ("boot_bridge_events.log", "latest.log"):
            remote_path = f"{sts_root['root']}/{relative_path}"
            quoted = quote_android_shell(remote_path)
            if sts_root["accessMode"] == "run-as":
                self.adb(["exec-out", "run-as", self.application_id or "", "sh", "-c", f"rm -f {quoted}"], allow_failure=True)
            else:
                self.adb_shell_script(f"rm -f {quoted}", allow_failure=True)

    def device_logcat_timestamp(self) -> str:
        result = self.adb_shell_script("date '+%m-%d %H:%M:%S.000' 2>/dev/null", timeout_seconds=5, allow_failure=True)
        if result.exit_code != 0:
            return ""
        for line in result.output.strip().splitlines():
            trimmed = line.strip()
            if re.match(r"^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}$", trimmed):
                return trimmed
        return ""

    def start_logcat_capture(self, output_directory: Path, since_timestamp: str = "") -> LogcatCapture:
        if not self.adb_path:
            raise RuntimeError("adb is not initialized.")
        output_directory.mkdir(parents=True, exist_ok=True)
        timestamp = file_timestamp()
        log_path = output_directory / f"harness-logcat-{timestamp}.txt"
        stderr_path = output_directory / f"harness-logcat-{timestamp}.stderr.txt"
        logcat_args = ["logcat", "-v", "threadtime", "-b", "main", "-b", "system", "-b", "crash"]
        logcat_args.extend(["-T", since_timestamp if since_timestamp.strip() else "1"])
        adb_args = self.build_adb_args(logcat_args)
        stdout_stream = log_path.open("wb")
        stderr_stream = stderr_path.open("wb")
        try:
            process = subprocess.Popen(
                [self.adb_path, *adb_args],
                cwd=str(self.repo_root),
                stdout=stdout_stream,
                stderr=stderr_stream,
            )
        except Exception:
            stdout_stream.close()
            stderr_stream.close()
            raise
        self.result["artifacts"]["harnessLogcat"] = str(log_path)
        self.result["artifacts"]["harnessLogcatStderr"] = str(stderr_path)
        return LogcatCapture(
            process=process,
            stdout_stream=stdout_stream,
            stderr_stream=stderr_stream,
            log_path=log_path,
            stderr_path=stderr_path,
            started_at=datetime.now(timezone.utc),
            command=format_command_for_log(self.adb_path, adb_args),
        )

    def stop_logcat_capture(self, capture: LogcatCapture | None) -> None:
        if capture is None:
            return
        ended = datetime.now(timezone.utc)
        stopped_by_harness = False
        exit_code: int | None = None
        try:
            if capture.process.poll() is None:
                stopped_by_harness = True
                capture.process.kill()
            try:
                capture.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass
            if capture.process.poll() is not None:
                exit_code = capture.process.returncode
        finally:
            capture.stdout_stream.close()
            capture.stderr_stream.close()

        stderr_tail = read_local_text_tail(capture.stderr_path, max_bytes=4000)
        self.operations.append(
            {
                "command": capture.command,
                "exitCode": exit_code,
                "startedAt": utc_timestamp(capture.started_at),
                "endedAt": utc_timestamp(ended),
                "durationMs": int((ended - capture.started_at).total_seconds() * 1000),
                "timedOut": False,
                "background": True,
                "stoppedByHarness": stopped_by_harness,
                "outputTail": limit_text(f"stdout: {capture.log_path}\nstderr: {capture.stderr_path}\n{stderr_tail}"),
            }
        )

    def harness_logcat_dump(self, output_directory: Path, since_timestamp: str = "") -> Path:
        output_directory.mkdir(parents=True, exist_ok=True)
        log_path = output_directory / f"harness-logcat-dump-{file_timestamp()}.txt"
        args = ["logcat", "-d", "-v", "threadtime", "-b", "main", "-b", "system", "-b", "crash"]
        if since_timestamp.strip():
            args.extend(["-T", since_timestamp])
        else:
            args.extend(["-t", "1500"])
        result = self.adb(args, timeout_seconds=30, allow_failure=True)
        if result.exit_code != 0 and since_timestamp.strip():
            fallback = ["logcat", "-d", "-v", "threadtime", "-b", "main", "-b", "system", "-b", "crash", "-t", "1500"]
            result = self.adb(fallback, timeout_seconds=30, allow_failure=True)
        log_path.write_text(result.output, encoding="utf-8")
        self.result["artifacts"]["harnessLogcat"] = str(log_path)
        return log_path

    def resolve_device_sts_root(self) -> dict[str, Any]:
        package_name = self.application_id or ""
        candidates = [
            f"/sdcard/Android/data/{package_name}/files/sts",
            f"/storage/emulated/0/Android/data/{package_name}/files/sts",
        ]
        for candidate in candidates:
            probe = self.adb_shell_script(f"ls {quote_android_shell(candidate)} >/dev/null 2>&1", allow_failure=True)
            if probe.exit_code == 0:
                return {"root": candidate, "accessMode": "shell"}
        run_as = self.adb(["exec-out", "run-as", package_name, "sh", "-c", "ls 'files/sts' >/dev/null 2>&1"], timeout_seconds=5, allow_failure=True)
        if run_as.exit_code == 0:
            return {"root": "files/sts", "accessMode": "run-as"}
        return {"root": candidates[0], "accessMode": "shell"}

    def resolve_device_storage_root(self, sts_root: dict[str, Any] | None = None) -> dict[str, Any]:
        sts_root = sts_root or self.resolve_device_sts_root()
        sts_root_path = str(sts_root["root"]).rstrip("/")
        storage_root = posixpath.dirname(sts_root_path) if sts_root_path else ""
        if not storage_root:
            raise RuntimeError(f"Could not derive storage root from STS root: {sts_root_path}")
        return {"root": storage_root, "accessMode": sts_root["accessMode"]}

    @staticmethod
    def remote_root_path(root_info: dict[str, Any], relative_path: str) -> str:
        trimmed = relative_path.strip().lstrip("/")
        root_path = str(root_info["root"]).rstrip("/")
        return root_path if not trimmed else f"{root_path}/{trimmed}"

    def read_remote_root_text(
        self,
        root_info: dict[str, Any],
        relative_path: str,
        tail_lines: int = 0,
        *,
        timeout_seconds: int = 5,
    ) -> str:
        remote_path = self.remote_root_path(root_info, relative_path)
        quoted = quote_android_shell(remote_path)
        script = f"if [ -f {quoted} ]; then tail -n {tail_lines} {quoted}; fi" if tail_lines > 0 else f"if [ -f {quoted} ]; then cat {quoted}; fi"
        if root_info["accessMode"] == "run-as":
            return self.adb(
                ["exec-out", "run-as", self.application_id or "", "sh", "-c", script],
                timeout_seconds=timeout_seconds,
                allow_failure=True,
            ).output
        return self.adb_shell_script(script, timeout_seconds=timeout_seconds, allow_failure=True).output

    def remote_root_script(
        self,
        root_info: dict[str, Any],
        script: str,
        *,
        timeout_seconds: int = 5,
        allow_failure: bool = True,
    ) -> CommandResult:
        if root_info["accessMode"] == "run-as":
            return self.adb(
                ["exec-out", "run-as", self.application_id or "", "sh", "-c", script],
                timeout_seconds=timeout_seconds,
                allow_failure=allow_failure,
            )
        return self.adb_shell_script(script, timeout_seconds=timeout_seconds, allow_failure=allow_failure)

    def remote_root_path_state(self, root_info: dict[str, Any], relative_path: str) -> dict[str, Any]:
        remote_path = self.remote_root_path(root_info, relative_path)
        quoted = quote_android_shell(remote_path)
        state_script = f"""if [ -e {quoted} ]; then
  echo exists=1
  if [ -f {quoted} ]; then
    echo type=file
    size=$(wc -c < {quoted} 2>/dev/null | tr -d '[:space:]')
    echo bytes=$size
  elif [ -d {quoted} ]; then
    echo type=directory
    echo bytes=0
  else
    echo type=other
    echo bytes=0
  fi
  mtime=$(stat -c %Y {quoted} 2>/dev/null || echo '')
  echo mtimeEpochSeconds=$mtime
else
  echo exists=0
fi
"""
        result = self.remote_root_script(root_info, state_script)
        return self.parse_remote_path_state_output(relative_path, result.output)

    def write_remote_root_text(
        self,
        root_info: dict[str, Any],
        relative_path: str,
        text: str,
        *,
        timeout_seconds: int = 10,
    ) -> None:
        trimmed = relative_path.strip().lstrip("/")
        if not trimmed:
            raise ValueError("relative device path must not be empty")
        remote_path = self.remote_root_path(root_info, trimmed)
        parent_dir = posixpath.dirname(remote_path)
        quoted_parent = quote_android_shell(parent_dir)
        quoted_remote_path = quote_android_shell(remote_path)
        normalized_text = text.replace("\r\n", "\n")
        heredoc_token = "__STS_HARNESS_EOF__"
        while heredoc_token in normalized_text:
            heredoc_token += "_X"
        script = (
            f"mkdir -p {quoted_parent} || exit 1\n"
            f"cat <<'{heredoc_token}' > {quoted_remote_path}\n"
            f"{normalized_text}\n"
            f"{heredoc_token}\n"
        )
        result = self.remote_root_script(root_info, script, timeout_seconds=timeout_seconds, allow_failure=False)
        if result.exit_code != 0:
            raise RuntimeError(f"Failed to write device file: {remote_path}")

    def read_remote_sts_text(
        self,
        sts_root: dict[str, Any],
        relative_path: str,
        tail_lines: int = 0,
        *,
        timeout_seconds: int = 5,
    ) -> str:
        return self.read_remote_root_text(sts_root, relative_path, tail_lines=tail_lines, timeout_seconds=timeout_seconds)

    def remote_sts_root_script(
        self,
        sts_root: dict[str, Any],
        script: str,
        *,
        timeout_seconds: int = 5,
        allow_failure: bool = True,
    ) -> CommandResult:
        return self.remote_root_script(
            sts_root,
            script,
            timeout_seconds=timeout_seconds,
            allow_failure=allow_failure,
        )

    def remote_sts_path_state(self, sts_root: dict[str, Any], relative_path: str) -> dict[str, Any]:
        return self.remote_root_path_state(sts_root, relative_path)

    @staticmethod
    def parse_remote_path_state_output(relative_path: str, text: str | None) -> dict[str, Any]:
        exists = False
        item_type = None
        bytes_value = None
        mtime_epoch_seconds = None
        child_count = None
        jar_count = None
        for line in (text or "").splitlines():
            trimmed_line = line.strip()
            if trimmed_line == "exists=1":
                exists = True
            elif trimmed_line.startswith("type="):
                item_type = trimmed_line[len("type=") :]
            elif trimmed_line.startswith("bytes="):
                try:
                    bytes_value = int(trimmed_line[len("bytes=") :])
                except ValueError:
                    pass
            elif trimmed_line.startswith("mtimeEpochSeconds="):
                try:
                    mtime_epoch_seconds = int(trimmed_line[len("mtimeEpochSeconds=") :])
                except ValueError:
                    pass
            elif trimmed_line.startswith("childCount="):
                try:
                    child_count = int(trimmed_line[len("childCount=") :])
                except ValueError:
                    pass
            elif trimmed_line.startswith("jarCount="):
                try:
                    jar_count = int(trimmed_line[len("jarCount=") :])
                except ValueError:
                    pass
        return {
            "relativePath": relative_path,
            "exists": exists,
            "type": item_type,
            "bytes": bytes_value,
            "mtimeEpochSeconds": mtime_epoch_seconds,
            "childCount": child_count,
            "jarCount": jar_count,
        }

    def remote_app_path_state(self, relative_path: str) -> dict[str, Any]:
        package_name = self.application_id or ""
        trimmed = relative_path.strip().lstrip("/")
        if not trimmed:
            raise ValueError("relative app path must not be empty")
        quoted = quote_android_shell(trimmed)
        state_script = f"""if [ -e {quoted} ]; then
  echo exists=1
  if [ -f {quoted} ]; then
    echo type=file
    size=$(wc -c < {quoted} 2>/dev/null | tr -d '[:space:]')
    echo bytes=$size
  elif [ -d {quoted} ]; then
    echo type=directory
    echo bytes=0
    child_count=$(find {quoted} -mindepth 1 -maxdepth 1 2>/dev/null | wc -l | tr -d '[:space:]')
    jar_count=$(find {quoted} -type f -name '*.jar' 2>/dev/null | wc -l | tr -d '[:space:]')
    echo childCount=$child_count
    echo jarCount=$jar_count
  else
    echo type=other
    echo bytes=0
  fi
  mtime=$(stat -c %Y {quoted} 2>/dev/null || echo '')
  echo mtimeEpochSeconds=$mtime
else
  echo exists=0
fi
"""
        result = self.adb(
            ["exec-out", "run-as", package_name, "sh", "-c", state_script],
            timeout_seconds=10,
            allow_failure=True,
        )
        if result.exit_code != 0:
            state = self.parse_remote_path_state_output(relative_path, "")
            state["error"] = limit_text(result.output, 2000)
            return state
        return self.parse_remote_path_state_output(relative_path, result.output)

    def clear_startup_caches(self) -> dict[str, Any]:
        sts_root = self.resolve_device_sts_root()
        quoted_sts_root = quote_android_shell(str(sts_root["root"]))
        external_script = f"""
cd {quoted_sts_root} || exit 1
rm -f .mts_classpath_cache .mts_patch_cache desktop-1.0-modded.jar mts_patch_cache_debug.log
rm -rf package mts_patch_cache
"""
        external_result = self.remote_sts_root_script(
            sts_root,
            external_script,
            timeout_seconds=20,
            allow_failure=True,
        )
        private_script = """
rm -rf files/mts_patch_cache
rm -f files/sts/.mts_classpath_cache files/sts/.mts_patch_cache files/sts/desktop-1.0-modded.jar files/sts/mts_patch_cache_debug.log
rm -rf files/sts/package files/sts/mts_patch_cache
"""
        private_result = self.adb(
            ["exec-out", "run-as", self.application_id or "", "sh", "-c", private_script],
            timeout_seconds=20,
            allow_failure=True,
        )
        summary = {
            "storage": sts_root,
            "externalExitCode": external_result.exit_code,
            "externalOutputTail": limit_text(external_result.output, 2000),
            "privateExitCode": private_result.exit_code,
            "privateOutputTail": limit_text(private_result.output, 2000),
        }
        self.operations.append(
            {
                "command": "clear-startup-caches",
                "exitCode": 0 if external_result.exit_code == 0 and private_result.exit_code == 0 else 1,
                "startedAt": utc_timestamp(),
                "endedAt": utc_timestamp(),
                "durationMs": 0,
                "timedOut": False,
                "outputTail": json.dumps(summary, ensure_ascii=False),
            }
        )
        return summary

    def startup_cache_state(self) -> dict[str, Any]:
        sts_root = self.resolve_device_sts_root()
        return {
            "storage": sts_root,
            "classpathMarker": self.remote_sts_path_state(sts_root, ".mts_classpath_cache"),
            "legacyExternalPatchMarker": self.remote_sts_path_state(sts_root, ".mts_patch_cache"),
            "legacyExternalPatchJar": self.remote_sts_path_state(sts_root, "desktop-1.0-modded.jar"),
            "legacyExternalPackageDir": self.remote_sts_path_state(sts_root, "package"),
            "privatePatchDir": self.remote_app_path_state("files/mts_patch_cache"),
            "privatePatchMarker": self.remote_app_path_state("files/mts_patch_cache/.mts_patch_cache"),
            "privatePatchJar": self.remote_app_path_state("files/mts_patch_cache/desktop-1.0-modded.jar"),
            "privatePackageDir": self.remote_app_path_state("files/mts_patch_cache/package"),
            "loadoutScanCacheDir": self.remote_app_path_state("files/mts_patch_cache/loadout-scan-cache"),
        }

    def build_single_room_spec_text(self) -> str:
        spec_file = self.options.single_room_spec.strip()
        if spec_file:
            path = self.resolve_repo_path(spec_file)
            if not path.is_file():
                raise RuntimeError(f"Single-room spec file not found: {path}")
            self.result["artifacts"]["singleRoomInputSpec"] = str(path)
            return path.read_text(encoding="utf-8")

        character = self.options.single_room_character.strip()
        monster = self.options.single_room_monster.strip()
        cards = split_csv_tokens(self.options.single_room_cards)
        if not character:
            raise RuntimeError("single-room requires -SingleRoomCharacter or -SingleRoomSpec.")
        if not monster:
            raise RuntimeError("single-room requires -SingleRoomMonster or -SingleRoomSpec.")
        if not cards:
            raise RuntimeError("single-room requires at least one card through -SingleRoomCards or -SingleRoomSpec.")
        lines = [
            "# Managed by SlayTheAmethyst harness.",
            "schemaVersion=1",
            f"character={encode_properties_value(character)}",
            f"monster={encode_properties_value(monster)}",
            f"cards={encode_properties_value(','.join(cards))}",
            "",
        ]
        return "\n".join(lines)

    @staticmethod
    def parse_summary_key_values(text: str | None) -> dict[str, str]:
        values: dict[str, str] = {}
        for line in (text or "").splitlines():
            if ":" not in line:
                continue
            key, value = line.split(":", 1)
            values[key.strip()] = value.strip()
        return values

    @classmethod
    def parse_steam_cloud_summary(cls, text: str | None) -> dict[str, Any]:
        values = cls.parse_summary_key_values(text)
        return {
            "outcome": values.get("Outcome", ""),
            "operation": values.get("Operation", ""),
            "account": values.get("Account", ""),
            "startedAt": values.get("Started At", ""),
            "completedAt": values.get("Completed At", ""),
            "durationMs": cls.parse_optional_int(values.get("Duration Ms")),
            "failureSummary": values.get("Failure Summary", ""),
            "currentStage": values.get("Current Stage", ""),
        }

    @classmethod
    def parse_steam_cloud_push_summary(cls, text: str | None) -> dict[str, Any]:
        values = cls.parse_summary_key_values(text)
        return {
            "completedAt": values.get("Completed At", ""),
            "uploadedFiles": cls.parse_optional_int(values.get("Uploaded Files")),
            "uploadedBytes": cls.parse_optional_int(values.get("Uploaded Bytes")),
            "deletedRemoteFiles": cls.parse_optional_int(values.get("Deleted Remote Files")),
            "remoteFilesAfterPush": cls.parse_optional_int(values.get("Remote Files After Push")),
        }

    @staticmethod
    def parse_optional_int(value: str | None) -> int | None:
        if value is None:
            return None
        text = value.strip()
        if not text:
            return None
        try:
            return int(text)
        except ValueError:
            return None

    @staticmethod
    def remote_path_state_changed(before: dict[str, Any] | None, after: dict[str, Any] | None) -> bool:
        before = before or {}
        after = after or {}
        keys = ("exists", "type", "bytes", "mtimeEpochSeconds")
        return any(before.get(key) != after.get(key) for key in keys)

    def build_cloud_sync_payload_text(self) -> str:
        source_file = self.options.cloud_sync_source_file.strip()
        if source_file:
            path = self.resolve_repo_path(source_file)
            if not path.is_file():
                raise RuntimeError(f"Cloud sync source file not found: {path}")
            self.result["artifacts"]["cloudSyncSourceFile"] = str(path)
            text = path.read_text(encoding="utf-8")
        elif self.options.cloud_sync_payload.strip():
            text = self.options.cloud_sync_payload
        else:
            text = "\n".join(
                [
                    "# Managed by SlayTheAmethyst harness.",
                    f"updatedAtUtc={utc_timestamp()}",
                    f"deviceSerial={self.resolved_device_serial}",
                    f"launchMode={self.options.launch_mode}",
                    "",
                ]
            )
        if not text.endswith("\n"):
            text += "\n"
        return text

    def collect_remote_text_snapshot(
        self,
        root_info: dict[str, Any],
        relative_path: str,
        local_path: Path,
        *,
        tail_lines: int = 0,
        parser: Any = None,
    ) -> dict[str, Any]:
        state = self.remote_root_path_state(root_info, relative_path)
        text = self.read_remote_root_text(root_info, relative_path, tail_lines=tail_lines, timeout_seconds=10)
        artifact = ""
        if state.get("exists") and state.get("type") == "file":
            local_path.parent.mkdir(parents=True, exist_ok=True)
            local_path.write_text(text, encoding="utf-8")
            artifact = str(local_path)
        record: dict[str, Any] = {
            "relativePath": relative_path,
            "state": state,
            "artifact": artifact,
        }
        if parser is not None:
            record["parsed"] = parser(text)
        return record

    def collect_steam_cloud_snapshot(
        self,
        storage_root: dict[str, Any],
        sts_root: dict[str, Any],
        output_directory: Path,
        *,
        trigger_relative_path: str,
        status_snapshot: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        output_directory.mkdir(parents=True, exist_ok=True)
        steam_cloud_files = {
            "lastOperationSummary": self.collect_remote_text_snapshot(
                storage_root,
                "steam-cloud/last-operation-summary.txt",
                output_directory / "steam-cloud" / "last-operation-summary.txt",
                parser=self.parse_steam_cloud_summary,
            ),
            "pushSummary": self.collect_remote_text_snapshot(
                storage_root,
                "steam-cloud/push-summary.txt",
                output_directory / "steam-cloud" / "push-summary.txt",
                parser=self.parse_steam_cloud_push_summary,
            ),
            "pullSummary": self.collect_remote_text_snapshot(
                storage_root,
                "steam-cloud/pull-summary.txt",
                output_directory / "steam-cloud" / "pull-summary.txt",
            ),
            "manifest": self.collect_remote_text_snapshot(
                storage_root,
                "steam-cloud/manifest.json",
                output_directory / "steam-cloud" / "manifest.json",
            ),
            "baseline": self.collect_remote_text_snapshot(
                storage_root,
                "steam-cloud/sync-baseline.json",
                output_directory / "steam-cloud" / "sync-baseline.json",
            ),
        }
        runtime_files = {
            "latestLog": self.collect_remote_text_snapshot(
                sts_root,
                "latest.log",
                output_directory / "sts" / "latest.log",
                tail_lines=400,
            ),
            "bootBridgeEvents": self.collect_remote_text_snapshot(
                sts_root,
                "boot_bridge_events.log",
                output_directory / "sts" / "boot_bridge_events.log",
            ),
            "triggerFile": self.collect_remote_text_snapshot(
                sts_root,
                trigger_relative_path,
                output_directory / "sts" / trigger_relative_path.replace("/", os.sep),
            ),
        }
        snapshot = {
            "collectedAt": utc_timestamp(),
            "storageRoots": {"sts": sts_root, "storage": storage_root},
            "statusSnapshot": status_snapshot,
            "steamCloud": steam_cloud_files,
            "runtime": runtime_files,
        }
        snapshot_path = output_directory / "snapshot.json"
        snapshot_path.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        snapshot["artifact"] = str(snapshot_path)
        return snapshot

    def evaluate_steam_cloud_snapshot(
        self,
        snapshot: dict[str, Any],
        *,
        baseline_last_operation_text: str,
        baseline_last_operation_state: dict[str, Any],
    ) -> dict[str, Any]:
        last_operation = snapshot.get("steamCloud", {}).get("lastOperationSummary", {})
        last_operation_state = last_operation.get("state", {}) if isinstance(last_operation, dict) else {}
        last_operation_artifact = last_operation.get("artifact", "") if isinstance(last_operation, dict) else ""
        last_operation_text = ""
        if last_operation_artifact:
            last_operation_text = Path(last_operation_artifact).read_text(encoding="utf-8", errors="replace")
        parsed = last_operation.get("parsed", {}) if isinstance(last_operation, dict) else {}
        is_new_last_operation = self.remote_path_state_changed(baseline_last_operation_state, last_operation_state)
        if last_operation_text.strip() != baseline_last_operation_text.strip():
            is_new_last_operation = True
        if not is_new_last_operation:
            return {"complete": False, "failed": False, "reason": "no-new-summary"}
        outcome = str(parsed.get("outcome", "")).strip().upper()
        operation = str(parsed.get("operation", "")).strip().lower()
        if outcome == "FAILED":
            return {
                "complete": False,
                "failed": True,
                "reason": "summary-failed",
                "summary": parsed,
            }
        if outcome == "SUCCESS" and operation in STEAM_CLOUD_SUCCESS_OPERATIONS:
            push_summary = snapshot.get("steamCloud", {}).get("pushSummary", {})
            push_parsed = push_summary.get("parsed", {}) if isinstance(push_summary, dict) else {}
            return {
                "complete": True,
                "failed": False,
                "reason": "summary-success",
                "summary": parsed,
                "pushSummary": push_parsed,
            }
        return {"complete": False, "failed": False, "reason": "summary-not-finished", "summary": parsed}

    def ensure_single_room_device_spec(self) -> str:
        if self.options.single_room_device_spec.strip():
            return self.options.single_room_device_spec.strip()

        local_spec = self.resolved_out_dir() / SINGLE_ROOM_DEFAULT_REMOTE_SPEC
        local_spec.write_text(self.build_single_room_spec_text(), encoding="utf-8")
        self.result["artifacts"]["singleRoomSpec"] = str(local_spec)

        sts_root = self.resolve_device_sts_root()
        remote_relative = f"config/{SINGLE_ROOM_DEFAULT_REMOTE_SPEC}"
        remote_path = f"{sts_root['root']}/{remote_relative}"
        if sts_root["accessMode"] == "run-as":
            temp_remote = f"/data/local/tmp/sts-harness-{file_timestamp()}-{SINGLE_ROOM_DEFAULT_REMOTE_SPEC}"
            self.adb(["push", str(local_spec), temp_remote], timeout_seconds=30)
            self.adb(["shell", "chmod", "0644", temp_remote], timeout_seconds=5, allow_failure=True)
            copy_script = (
                "mkdir -p files/sts/config && "
                f"cat {quote_android_shell(temp_remote)} > files/sts/{remote_relative}"
            )
            try:
                self.adb(
                    ["exec-out", "run-as", self.application_id or "", "sh", "-c", copy_script],
                    timeout_seconds=10,
                )
            finally:
                self.adb(["shell", "rm", "-f", temp_remote], timeout_seconds=5, allow_failure=True)
            return f"files/sts/{remote_relative}"

        parent = f"{sts_root['root']}/config"
        self.adb_shell_script(f"mkdir -p {quote_android_shell(parent)}", timeout_seconds=10)
        self.adb(["push", str(local_spec), remote_path], timeout_seconds=30)
        return remote_path

    def desktop_jar_patch_snapshot(self, sts_root: dict[str, Any]) -> dict[str, Any]:
        desktop_jar = self.remote_sts_path_state(sts_root, "desktop-1.0.jar")
        temp_jar = self.remote_sts_path_state(sts_root, "desktop-1.0.jar.patching.tmp")
        backup_jar = self.remote_sts_path_state(sts_root, "desktop-1.0.jar.patching.backup")
        return {
            "desktopJar": desktop_jar,
            "tempJar": temp_jar,
            "backupJar": backup_jar,
            "inProgress": bool(temp_jar["exists"] or backup_jar["exists"]),
        }

    @staticmethod
    def parse_boot_bridge_events(text: str | None) -> dict[str, Any]:
        latest = None
        terminal = None
        count = 0
        for line in re.split(r"\r?\n", text or ""):
            trimmed = line.strip()
            if not trimmed:
                continue
            parts = trimmed.split("\t", 2)
            event_type = parts[0].strip().upper()
            progress = None
            if len(parts) >= 2:
                try:
                    progress = int(parts[1].strip())
                except ValueError:
                    pass
            message = parts[2].strip() if len(parts) >= 3 else ""
            event = {"type": event_type, "progress": progress, "message": message}
            latest = event
            count += 1
            if event_type in ("READY", "FAIL"):
                terminal = event
        return {"eventCount": count, "latestEvent": latest, "terminalEvent": terminal}

    @staticmethod
    def find_crash_marker(text: str | None) -> str | None:
        for marker in (
            "Game crashed.",
            "Exception occurred in CardCrawlGame render method!",
            'Exception in thread "LWJGL Application"',
            "Forced runtime crash for expected-exit verification",
        ):
            if text_contains(text, marker):
                return marker
        return None

    @staticmethod
    def find_single_room_result(text: str | None) -> dict[str, Any] | None:
        if not text or not text.strip():
            return None
        result_line = None
        for line in re.split(r"\r?\n", text):
            if SINGLE_ROOM_RESULT_PREFIX in line:
                result_line = line.strip()
        if result_line is None:
            return None
        payload = result_line.split(SINGLE_ROOM_RESULT_PREFIX, 1)[1].strip()
        values: dict[str, str] = {}
        for match in re.finditer(r"(\w+)=([^ ]+)", payload):
            values[match.group(1)] = match.group(2)
        return {
            "line": result_line,
            "outcome": values.get("outcome"),
            "character": values.get("character"),
            "monster": values.get("monster"),
            "turns": values.get("turns"),
            "playerHp": values.get("playerHp"),
            "monsterHp": values.get("monsterHp"),
            "detail": values.get("detail"),
        }

    @staticmethod
    def find_harness_logcat_crash(text: str | None, package_name: str) -> dict[str, Any] | None:
        if not text or not text.strip():
            return None
        lines = re.split(r"\r?\n", text)
        markers = (
            "FATAL EXCEPTION",
            "Fatal signal",
            "AndroidRuntime",
            "Game crashed.",
            "Game body patch failed before launch",
            "Exception occurred in CardCrawlGame render method!",
            'Exception in thread "LWJGL Application"',
            "java.lang.OutOfMemoryError",
        )
        process_names = tuple(
            name
            for name in (
                package_name,
                f"{package_name}:game" if package_name else "",
                f"{package_name}:steamcloud" if package_name else "",
                f"{package_name}:diag" if package_name else "",
                f"{package_name}:logcat" if package_name else "",
            )
            if name
        )
        package_needles = process_names + tuple(f"Process: {name}" for name in process_names) + tuple(
            f">>> {name}" for name in process_names
        )
        strong_package_needles = (
            tuple(f"Process: {name}" for name in process_names)
            + tuple(f"Cmdline: {name}" for name in process_names)
            + tuple(f"Cmd line: {name}" for name in process_names)
            + tuple(f">>> {name}" for name in process_names)
        )
        generic_crash_markers = (
            "FATAL EXCEPTION",
            "Fatal signal",
            "AndroidRuntime",
            "java.lang.OutOfMemoryError",
        )
        for index, line in enumerate(lines):
            marker_matched = None
            for marker in markers:
                if text_contains(line, marker):
                    marker_matched = marker
                    break
            if marker_matched is None:
                for needle in package_needles:
                    if text_contains(line, needle):
                        if (
                            text_contains(line, f"Process: {package_name}")
                            or text_contains(line, f">>> {package_name}")
                            or text_contains(line, f"Cmdline: {package_name}")
                            or text_contains(line, "Force finishing")
                        ):
                            marker_matched = needle
                        break
            if marker_matched is None:
                continue
            start = max(0, index - 12)
            end = min(len(lines) - 1, index + 90)
            window_text = "\n".join(lines[start : end + 1])
            package_matched = any(text_contains(window_text, needle) for needle in package_needles)
            strong_package_matched = any(text_contains(window_text, needle) for needle in strong_package_needles)
            runtime_log_marker = marker_matched in (
                "Game crashed.",
                "Game body patch failed before launch",
                "Exception occurred in CardCrawlGame render method!",
                'Exception in thread "LWJGL Application"',
            )
            if marker_matched in generic_crash_markers and not strong_package_matched:
                continue
            if not package_matched and not runtime_log_marker:
                continue
            return {
                "marker": marker_matched,
                "line": line.strip(),
                "packageMatched": package_matched or strong_package_matched,
                "excerpt": limit_text(window_text, 5000),
            }
        return None

    @staticmethod
    def last_non_blank_line(text: str | None) -> str | None:
        last = None
        for line in re.split(r"\r?\n", text or ""):
            trimmed = line.strip()
            if trimmed:
                last = trimmed
        return last

    @staticmethod
    def extract_startup_cache_log_evidence(text: str | None, max_lines: int = 80) -> dict[str, Any]:
        evidence_lines: list[str] = []
        timing_lines: list[dict[str, Any]] = []
        saw_cache_hit = False
        saw_cache_build = False
        saw_cache_miss = False
        for raw_line in re.split(r"\r?\n", text or ""):
            line = raw_line.strip()
            if not line:
                continue
            matched = any(pattern in line for pattern in STARTUP_CACHE_EVIDENCE_PATTERNS)
            if matched:
                evidence_lines.append(line)
                if "Launching cached MTS patch jar" in line:
                    saw_cache_hit = True
                if "Writing MTS patch cache jar" in line or "MTS patch cache is ready" in line:
                    saw_cache_build = True
                if "Patch cache miss:" in line:
                    saw_cache_miss = True
            if matched or "took=" in line or " elapsedMs=" in line or " took " in line:
                timing_match = re.search(
                    r"(?P<label>.*?)(?:\s+took=|\s+took\s+|\s+elapsedMs=|Time Elapsed:\s*)"
                    r"(?P<ms>\d+(?:\.\d+)?)ms",
                    line,
                )
                if timing_match:
                    label = timing_match.group("label").strip()
                    try:
                        elapsed_ms: float | int = float(timing_match.group("ms"))
                        if elapsed_ms.is_integer():
                            elapsed_ms = int(elapsed_ms)
                    except ValueError:
                        elapsed_ms = timing_match.group("ms")
                    timing_lines.append(
                        {
                            "label": limit_text(label, 180),
                            "elapsedMs": elapsed_ms,
                            "line": line,
                        }
                    )
        if saw_cache_hit:
            mode = "cache-hit"
        elif saw_cache_build:
            mode = "cache-build"
        elif saw_cache_miss:
            mode = "cache-miss"
        else:
            mode = "unknown"
        return {
            "mode": mode,
            "sawCacheHit": saw_cache_hit,
            "sawCacheBuild": saw_cache_build,
            "sawCacheMiss": saw_cache_miss,
            "evidenceLines": evidence_lines[-max_lines:],
            "timings": timing_lines[-max_lines:],
        }

    def process_pid_text(self, process_name: str) -> str:
        result = self.adb_shell_script(f"pidof {quote_android_shell(process_name)} 2>/dev/null || true", allow_failure=True)
        return result.output.strip()

    def package_version_info(self) -> dict[str, Any]:
        quoted = quote_android_shell(self.application_id or "")
        result = self.adb_shell_script(f"dumpsys package {quoted} 2>/dev/null | grep -E 'version(Name|Code)=' || true", timeout_seconds=5, allow_failure=True)
        version_name = None
        version_code = None
        for line in result.output.splitlines():
            trimmed = line.strip()
            if trimmed.startswith("versionName="):
                version_name = trimmed[len("versionName=") :]
            elif trimmed.startswith("versionCode="):
                version_code = trimmed[len("versionCode=") :].split(" ")[0]
        return {"versionName": version_name, "versionCode": version_code}

    def harness_status(self, harness_logcat_text: str | None = None, harness_logcat_path: str = "") -> dict[str, Any]:
        sts_root = self.resolve_device_sts_root()
        boot_text = self.read_remote_sts_text(sts_root, "boot_bridge_events.log")
        latest_log_tail = self.read_remote_sts_text(sts_root, "latest.log", tail_lines=120)
        desktop_jar_patch = self.desktop_jar_patch_snapshot(sts_root)
        boot = self.parse_boot_bridge_events(boot_text)
        crash_marker = self.find_crash_marker(latest_log_tail)
        single_room_result = self.find_single_room_result(latest_log_tail)

        package_name = self.application_id or ""
        launcher_pid = self.process_pid_text(package_name)
        game_pid = self.process_pid_text(f"{package_name}:game")
        steamcloud_pid = self.process_pid_text(f"{package_name}:steamcloud")
        diag_pid = self.process_pid_text(f"{package_name}:diag")
        logcat_pid = self.process_pid_text(f"{package_name}:logcat")

        runtime_signal_state = None
        terminal = boot["terminalEvent"]
        if terminal is not None:
            runtime_signal_state = terminal["type"]
        elif crash_marker is not None:
            runtime_signal_state = "CRASH_MARKER"

        observed_state = "NOT_RUNNING"
        if terminal is not None and terminal["type"] == "FAIL":
            observed_state = "FAIL"
        elif crash_marker is not None:
            observed_state = "CRASH_MARKER"
        elif single_room_result is not None:
            observed_state = "SINGLE_ROOM_COMPLETE"
            runtime_signal_state = "SINGLE_ROOM_COMPLETE"
        elif terminal is not None and terminal["type"] == "READY" and game_pid.strip():
            observed_state = "READY"
        elif launcher_pid.strip() and desktop_jar_patch["inProgress"]:
            observed_state = "PATCHING_DESKTOP_JAR"
        elif game_pid.strip():
            observed_state = "RUNNING_WITHOUT_TERMINAL_EVENT"
        elif steamcloud_pid.strip():
            observed_state = "STEAM_CLOUD_SYNC_RUNNING"
        elif launcher_pid.strip():
            observed_state = "LAUNCHER_RUNNING"

        harness_logcat = None
        if harness_logcat_text is not None:
            crash = self.find_harness_logcat_crash(harness_logcat_text, package_name)
            if crash is not None and runtime_signal_state is None:
                runtime_signal_state = "LOGCAT_CRASH"
            harness_logcat = {
                "artifact": harness_logcat_path,
                "lastNonBlankLine": self.last_non_blank_line(harness_logcat_text),
                "crash": crash,
            }

        return {
            "observedState": observed_state,
            "runtimeSignalState": runtime_signal_state,
            "applicationId": package_name,
            "deviceSerial": self.resolved_device_serial,
            "package": self.package_version_info(),
            "processes": {
                "launcher": launcher_pid,
                "game": game_pid,
                "steamcloud": steamcloud_pid,
                "diag": diag_pid,
                "logcat": logcat_pid,
            },
            "storage": sts_root,
            "desktopJarPatch": desktop_jar_patch,
            "bootBridge": boot,
            "latestLog": {
                "lastNonBlankLine": self.last_non_blank_line(latest_log_tail),
                "crashMarker": crash_marker,
                "singleRoomResult": single_room_result,
            },
            "harnessLogcat": harness_logcat,
        }

    def update_status_harness_logcat(self, status: dict[str, Any] | None, logcat_path: Path | str) -> None:
        if status is None or not str(logcat_path).strip():
            return
        previous_crash = None
        if status.get("harnessLogcat") is not None:
            previous_crash = status["harnessLogcat"].get("crash")
        logcat_text = read_local_text_tail(logcat_path, max_bytes=262144)
        crash = self.find_harness_logcat_crash(logcat_text, self.application_id or "")
        if crash is None and previous_crash is not None:
            crash = previous_crash
        status["harnessLogcat"] = {
            "artifact": str(logcat_path),
            "lastNonBlankLine": self.last_non_blank_line(logcat_text),
            "crash": crash,
        }
        if crash is not None and status.get("observedState") not in ("READY", "FAIL", "CRASH_MARKER", "LOGCAT_CRASH"):
            status["observedState"] = "LOGCAT_CRASH"
            status["runtimeSignalState"] = "LOGCAT_CRASH"
        elif crash is not None and status.get("runtimeSignalState") is None:
            status["runtimeSignalState"] = "LOGCAT_CRASH"

    def wait_harness_status(self, logcat_capture: LogcatCapture | None = None) -> dict[str, Any]:
        safe_timeout = max(1, self.options.timeout_seconds)
        safe_poll = max(0.25, self.options.poll_interval_seconds)
        deadline = time.monotonic() + safe_timeout
        latest_status = None
        saw_game_process = False
        game_exit_first_seen = None
        while True:
            logcat_text = None
            logcat_path = ""
            if logcat_capture is not None:
                logcat_path = str(logcat_capture.log_path)
                logcat_text = read_local_text_tail(logcat_capture.log_path, max_bytes=262144)
            latest_status = self.harness_status(logcat_text, logcat_path)
            terminal_states = (
                ("SINGLE_ROOM_COMPLETE", "FAIL", "CRASH_MARKER")
                if self.options.autoplay_mode == "single_room"
                else ("READY", "FAIL", "CRASH_MARKER")
            )
            if latest_status["observedState"] in terminal_states:
                return latest_status
            if latest_status.get("harnessLogcat") is not None and latest_status["harnessLogcat"].get("crash") is not None:
                latest_status["observedState"] = "LOGCAT_CRASH"
                latest_status["runtimeSignalState"] = "LOGCAT_CRASH"
                return latest_status

            if latest_status["processes"]["game"].strip():
                saw_game_process = True
                game_exit_first_seen = None
            elif saw_game_process:
                now = time.monotonic()
                if game_exit_first_seen is None:
                    game_exit_first_seen = now
                elif now - game_exit_first_seen >= safe_poll:
                    latest_status["observedState"] = "PROCESS_EXITED"
                    if latest_status.get("runtimeSignalState") is None:
                        latest_status["runtimeSignalState"] = "PROCESS_EXITED"
                    return latest_status

            if time.monotonic() >= deadline:
                return latest_status
            time.sleep(safe_poll)

    def set_result_success(self, success: bool, status: str, message: str) -> None:
        self.result["success"] = success
        self.result["status"] = status
        self.result["message"] = message

    def complete_result(self) -> None:
        ended_at = datetime.now(timezone.utc)
        self.result["endedAt"] = utc_timestamp(ended_at)
        self.result["durationMs"] = int((ended_at - self.started_at).total_seconds() * 1000)
        self.result["operations"] = self.operations

    def write_result(self, result_path: Path) -> None:
        self.complete_result()
        result_path.write_text(json.dumps(self.result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"Harness result: {result_path}")

    def run_command(self, resolved_out_dir: Path) -> int:
        command = self.options.command
        if command == "steam-cloud-sync":
            return self.harness_steam_cloud_sync(resolved_out_dir)
        ctx = self._build_context()

        if command in ("doctor", "install", "start", "stop", "logs", "screenshot", "status",
                       "mods", "set-mods", "smoke", "decompil", "agent-attach", "agent-detach",
                       "agent-list", "agent-status", "play", "hotreload", "perf", "single-room",
                       "startup-cache-profile", "console"):
            if command == "doctor":
                from scripts.tools.harness.doctor import run_doctor
                run_doctor(ctx)
                return 0
            elif command == "install":
                from scripts.tools.harness.install import run_install
                run_install(ctx)
                return 0
            elif command == "start":
                from scripts.tools.harness.run import run_start
                run_start(ctx)
                return 0
            elif command == "stop":
                from scripts.tools.harness.run import run_stop
                run_stop(ctx)
                return 0
            elif command == "logs":
                from scripts.tools.harness.logs import run_logs
                run_logs(ctx, resolved_out_dir)
                return 0
            elif command == "screenshot":
                from scripts.tools.harness.screenshot import run_screenshot
                run_screenshot(ctx, resolved_out_dir)
                return 0
            elif command == "status":
                from scripts.tools.harness.status import run_status
                run_status(ctx)
                return 0
            elif command == "mods":
                from scripts.tools.harness.mods import run_mods
                run_mods(ctx)
                return 0
            elif command == "set-mods":
                from scripts.tools.harness.mods import run_set_mods
                run_set_mods(ctx)
                return 0
            elif command == "decompil":
                from scripts.tools.harness.decompil import run_decompil
                info, success, status, message = run_decompil(ctx, resolved_out_dir)
                self.result["decompilInfo"] = info
                self.set_result_success(success, status, message)
                return 0
            elif command == "agent-attach":
                from scripts.tools.harness.agent import run_agent_attach
                run_agent_attach(ctx, resolved_out_dir)
                return 0
            elif command == "agent-detach":
                from scripts.tools.harness.agent import run_agent_detach
                run_agent_detach(ctx, resolved_out_dir)
                return 0
            elif command == "agent-list":
                from scripts.tools.harness.agent import run_agent_list
                run_agent_list(ctx, resolved_out_dir)
                return 0
            elif command == "agent-status":
                from scripts.tools.harness.agent import run_agent_status
                run_agent_status(ctx, resolved_out_dir)
                return 0
            elif command == "play":
                from scripts.tools.harness.play import run_play
                run_play(ctx, resolved_out_dir)
                return 0
            elif command == "console":
                from scripts.tools.harness.console import run_console
                run_console(ctx, resolved_out_dir)
                return 0
            elif command == "hotreload":
                from scripts.tools.harness.hotreload import run_hotreload
                run_hotreload(ctx, resolved_out_dir)
                return 0
            elif command == "perf":
                from scripts.tools.harness.perf import run_perf
                run_perf(ctx, resolved_out_dir)
                return 0
            elif command == "smoke":
                from scripts.tools.harness.smoke import run_smoke
                return run_smoke(ctx, resolved_out_dir)
            elif command == "single-room":
                ctx.options.autoplay = True
                ctx.options.autoplay_mode = "single_room"
                from scripts.tools.harness.smoke import run_smoke
                return run_smoke(ctx, resolved_out_dir)
            elif command == "startup-cache-profile":
                from scripts.tools.harness.startup_cache import run_startup_cache_profile
                return run_startup_cache_profile(ctx, resolved_out_dir)

        return 0

    def harness_steam_cloud_sync(self, resolved_out_dir: Path) -> int:
        sync_result: dict[str, Any] = {
            "triggerRelativePath": self.options.cloud_sync_relative_path.strip() or DEFAULT_CLOUD_SYNC_RELATIVE_PATH,
            "pullIntervalSeconds": max(1, int(self.options.cloud_sync_pull_interval_seconds)),
            "polls": [],
        }
        self.result["steamCloudSync"] = sync_result

        if not self.options.skip_install:
            self.harness_install()

        trigger_relative_path = sync_result["triggerRelativePath"]
        payload_text = self.build_cloud_sync_payload_text()
        payload_artifact = resolved_out_dir / "cloud-sync-trigger.txt"
        payload_artifact.write_text(payload_text, encoding="utf-8")
        self.result["artifacts"]["cloudSyncTriggerPayload"] = str(payload_artifact)
        sync_result["payloadBytes"] = len(payload_text.encode("utf-8"))

        status: dict[str, Any] | None = None
        final_snapshot: dict[str, Any] | None = None
        logcat_capture: LogcatCapture | None = None
        logcat_since = ""
        start_requested = False
        saw_running = False
        poll_index = 0

        sts_root = self.resolve_device_sts_root()
        storage_root = self.resolve_device_storage_root(sts_root)
        sync_result["storageRoots"] = {"sts": sts_root, "storage": storage_root}
        baseline_last_operation_state = self.remote_root_path_state(storage_root, "steam-cloud/last-operation-summary.txt")
        baseline_last_operation_text = self.read_remote_root_text(
            storage_root,
            "steam-cloud/last-operation-summary.txt",
            timeout_seconds=10,
        )
        sync_result["before"] = {
            "triggerFile": self.remote_sts_path_state(sts_root, trigger_relative_path),
            "lastOperationSummary": {
                "state": baseline_last_operation_state,
                "artifact": "",
            },
            "pushSummary": {
                "state": self.remote_root_path_state(storage_root, "steam-cloud/push-summary.txt"),
                "artifact": "",
            },
        }
        before_summary_path = resolved_out_dir / "before" / "steam-cloud" / "last-operation-summary.txt"
        if baseline_last_operation_text:
            before_summary_path.parent.mkdir(parents=True, exist_ok=True)
            before_summary_path.write_text(baseline_last_operation_text, encoding="utf-8")
            sync_result["before"]["lastOperationSummary"]["artifact"] = str(before_summary_path)
        before_push_text = self.read_remote_root_text(storage_root, "steam-cloud/push-summary.txt", timeout_seconds=10)
        before_push_path = resolved_out_dir / "before" / "steam-cloud" / "push-summary.txt"
        if before_push_text:
            before_push_path.parent.mkdir(parents=True, exist_ok=True)
            before_push_path.write_text(before_push_text, encoding="utf-8")
            sync_result["before"]["pushSummary"]["artifact"] = str(before_push_path)

        try:
            self.harness_stop()
            self.clear_runtime_signals()
            self.write_remote_root_text(sts_root, trigger_relative_path, payload_text, timeout_seconds=15)
            sync_result["afterWrite"] = {
                "triggerFile": self.remote_sts_path_state(sts_root, trigger_relative_path),
            }
            logcat_since = self.device_logcat_timestamp()
            try:
                logcat_capture = self.start_logcat_capture(resolved_out_dir, logcat_since)
            except Exception as exc:
                self.result["artifacts"]["harnessLogcatError"] = str(exc)
            self.harness_start()
            start_requested = True

            safe_timeout = max(1, self.options.timeout_seconds)
            safe_poll = max(0.25, self.options.poll_interval_seconds)
            pull_interval = max(1, int(self.options.cloud_sync_pull_interval_seconds))
            started_monotonic = time.monotonic()
            deadline = started_monotonic + safe_timeout
            next_pull_at = started_monotonic
            completion: dict[str, Any] | None = None

            while True:
                logcat_text = None
                logcat_path = ""
                if logcat_capture is not None:
                    logcat_path = str(logcat_capture.log_path)
                    logcat_text = read_local_text_tail(logcat_capture.log_path, max_bytes=262144)
                status = self.harness_status(logcat_text, logcat_path)
                self.result["statusSnapshot"] = status
                if status["observedState"] not in ("NOT_RUNNING",):
                    saw_running = True
                if status["observedState"] in ("FAIL", "CRASH_MARKER", "LOGCAT_CRASH"):
                    completion = {
                        "complete": False,
                        "failed": True,
                        "reason": f"runtime-{status['observedState'].lower()}",
                    }
                    break
                if saw_running and status["observedState"] == "NOT_RUNNING":
                    completion = {
                        "complete": False,
                        "failed": True,
                        "reason": "launcher-exited",
                    }
                    break

                now = time.monotonic()
                if now >= next_pull_at:
                    poll_index += 1
                    poll_dir = resolved_out_dir / "polls" / f"{poll_index:03d}"
                    snapshot = self.collect_steam_cloud_snapshot(
                        storage_root,
                        sts_root,
                        poll_dir,
                        trigger_relative_path=trigger_relative_path,
                        status_snapshot=status,
                    )
                    evaluation = self.evaluate_steam_cloud_snapshot(
                        snapshot,
                        baseline_last_operation_text=baseline_last_operation_text,
                        baseline_last_operation_state=baseline_last_operation_state,
                    )
                    poll_record = {
                        "index": poll_index,
                        "elapsedMs": int((now - started_monotonic) * 1000),
                        "artifactDir": str(poll_dir),
                        "statusSnapshot": status,
                        "evaluation": evaluation,
                        "snapshot": snapshot,
                    }
                    sync_result["polls"].append(poll_record)
                    final_snapshot = snapshot
                    if evaluation.get("failed"):
                        completion = evaluation
                        break
                    if evaluation.get("complete"):
                        completion = evaluation
                        break
                    next_pull_at = now + pull_interval

                if now >= deadline:
                    completion = {"complete": False, "failed": True, "reason": "timeout"}
                    break
                time.sleep(safe_poll)

            sync_result["completion"] = completion
            final_snapshot = self.collect_steam_cloud_snapshot(
                storage_root,
                sts_root,
                resolved_out_dir / "final",
                trigger_relative_path=trigger_relative_path,
                status_snapshot=status,
            )
            sync_result["finalSnapshot"] = final_snapshot
            try:
                self.harness_logs(resolved_out_dir)
            except Exception as exc:
                self.result["artifacts"]["logsError"] = str(exc)
        finally:
            if start_requested:
                try:
                    self.harness_stop()
                except Exception as exc:
                    self.result["artifacts"]["stopError"] = str(exc)
            if logcat_capture is not None:
                self.stop_logcat_capture(logcat_capture)
                self.update_status_harness_logcat(self.result.get("statusSnapshot"), logcat_capture.log_path)
            elif logcat_since.strip():
                try:
                    logcat_path = self.harness_logcat_dump(resolved_out_dir, logcat_since)
                    self.update_status_harness_logcat(self.result.get("statusSnapshot"), logcat_path)
                except Exception as exc:
                    self.result["artifacts"].setdefault("harnessLogcatError", str(exc))

        completion = sync_result.get("completion") or {}
        success = bool(completion.get("complete"))
        if success:
            summary = completion.get("summary", {})
            push_summary = completion.get("pushSummary", {})
            uploaded_files = push_summary.get("uploadedFiles")
            message = f"Steam Cloud sync completed via {summary.get('operation', '<unknown>')}."
            if uploaded_files is not None:
                message = f"{message} Uploaded files: {uploaded_files}."
            self.set_result_success(True, "STEAM_CLOUD_SYNC_COMPLETE", message)
            return 0

        failure_reason = str(completion.get("reason", "unknown"))
        observed_state = (status or {}).get("observedState", "STEAM_CLOUD_SYNC_FAILED")
        message = f"Steam Cloud sync did not complete: {failure_reason}."
        if self.result["artifacts"].get("logsZip"):
            message = f"{message} Logs zip: {self.result['artifacts']['logsZip']}"
        self.set_result_success(False, observed_state, message)
        return 1

    def run(self) -> int:
        resolved_out_dir = self.resolved_out_dir()
        resolved_out_dir.mkdir(parents=True, exist_ok=True)
        result_path = resolved_out_dir / "result.json"
        self.result = {
            "schemaVersion": 1,
            "command": self.options.command,
            "startedAt": utc_timestamp(self.started_at),
            "endedAt": None,
            "durationMs": None,
            "success": False,
            "status": "NOT_RUN",
            "message": "",
            "repoRoot": str(self.repo_root),
            "applicationId": None,
            "deviceSerial": self.resolved_device_serial,
            "launchMode": self.options.launch_mode,
            "forceJvmCrash": self.options.force_jvm_crash,
            "forceRuntimeCrash": self.options.force_runtime_crash,
            "autoplay": self.options.autoplay,
            "autoplaySaveMode": self.options.autoplay_save_mode,
            "autoplayMode": self.options.autoplay_mode,
            "disableCardObtainEffectOwnershipCompat": (
                self.options.disable_card_obtain_effect_ownership_compat
            ),
            "singleRoom": {
                "character": self.options.single_room_character,
                "monster": self.options.single_room_monster,
                "cards": split_csv_tokens(self.options.single_room_cards),
                "spec": self.options.single_room_spec,
                "deviceSpec": self.options.single_room_device_spec,
            },
            "startupCacheProfileOptions": {
                "cacheHitRuns": self.options.cache_hit_runs,
                "clearBeforeBuild": not self.options.no_clear_startup_cache,
            },
            "steamCloudSyncOptions": {
                "relativePath": self.options.cloud_sync_relative_path,
                "pullIntervalSeconds": self.options.cloud_sync_pull_interval_seconds,
                "sourceFile": self.options.cloud_sync_source_file,
            },
            "timeoutSeconds": self.options.timeout_seconds,
            "artifacts": {"outDir": str(resolved_out_dir), "resultJson": str(result_path)},
            "statusSnapshot": None,
            "deviceMods": None,
            "modSelection": None,
            "steamCloudSync": None,
            "operations": [],
            "error": None,
        }
        exit_code = 0
        try:
            self.initialize()
            self.result["applicationId"] = self.application_id
            self.result["deviceSerial"] = self.resolved_device_serial
            exit_code = self.run_command(resolved_out_dir)
        except Exception as exc:
            exit_code = 1
            self.result["error"] = {"type": f"{exc.__class__.__module__}.{exc.__class__.__name__}", "message": str(exc)}
            self.set_result_success(False, "ERROR", str(exc))
        finally:
            self.write_result(result_path)
        return exit_code
