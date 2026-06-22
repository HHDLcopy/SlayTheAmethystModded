
import json
import os
import re
import shutil
import subprocess
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from . import device_mods

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
    "single-room",
)
LAUNCH_MODES = ("mts_basemod", "mts", "vanilla")
AUTOPLAY_SAVE_MODES = ("fresh", "continue")
AUTOPLAY_MODES = ("normal", "single_room")
SINGLE_ROOM_DEFAULT_REMOTE_SPEC = "autoplay-single-room.properties"
SINGLE_ROOM_RESULT_PREFIX = "[amethyst-autoplay] single_room result "


def repo_root() -> Path:
    return Path(__file__).resolve().parents[3]


def utc_timestamp(value: datetime | None = None) -> str:
    value = value or datetime.now(timezone.utc)
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def file_timestamp() -> str:
    return datetime.now().strftime("%Y%m%d-%H%M%S")


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
    autoplay_save_mode: str
    autoplay_mode: str
    single_room_spec: str
    single_room_device_spec: str
    single_room_character: str
    single_room_monster: str
    single_room_cards: str
    disable_card_obtain_effect_ownership_compat: bool
    skip_install: bool
    no_stop_after_smoke: bool
    mods: list[str]
    mod_list_file: str
    enable_all_mods: bool
    disable_all_mods: bool


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

    def resolve_repo_path(self, path: str | Path) -> Path:
        path = Path(path)
        if path.is_absolute():
            return path.resolve()
        return (self.repo_root / path).resolve()

    def default_out_dir(self) -> Path:
        return self.repo_root / "debug-artifacts" / "harness" / f"{self.options.command}-{file_timestamp()}"

    def resolved_out_dir(self) -> Path:
        if not self.options.out_dir.strip():
            return self.default_out_dir()
        return self.resolve_repo_path(self.options.out_dir)

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

    def run_native(
        self,
        file_path: str | Path,
        arguments: list[str] | tuple[str, ...] = (),
        *,
        cwd: Path | None = None,
        timeout_seconds: int = 0,
        allow_failure: bool = False,
    ) -> CommandResult:
        started = datetime.now(timezone.utc)
        command = [str(file_path), *[str(argument) for argument in arguments]]
        output = ""
        exit_code = 0
        timed_out = False
        try:
            completed = subprocess.run(
                command,
                cwd=str(cwd or self.repo_root),
                text=True,
                encoding="utf-8",
                errors="replace",
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=timeout_seconds if timeout_seconds > 0 else None,
                check=False,
            )
            exit_code = completed.returncode
            output = "\n".join(part for part in (completed.stdout, completed.stderr) if part)
        except subprocess.TimeoutExpired as exc:
            timed_out = True
            exit_code = -1
            stdout = exc.stdout.decode("utf-8", errors="replace") if isinstance(exc.stdout, bytes) else (exc.stdout or "")
            stderr = exc.stderr.decode("utf-8", errors="replace") if isinstance(exc.stderr, bytes) else (exc.stderr or "")
            output = "\n".join(part for part in (stdout, stderr) if part)

        ended = datetime.now(timezone.utc)
        operation = {
            "command": format_command_for_log(file_path, list(arguments)),
            "exitCode": exit_code,
            "startedAt": utc_timestamp(started),
            "endedAt": utc_timestamp(ended),
            "durationMs": int((ended - started).total_seconds() * 1000),
            "timedOut": timed_out,
            "outputTail": limit_text(output),
        }
        self.operations.append(operation)

        if timed_out and not allow_failure:
            raise RuntimeError(f"Command timed out after {timeout_seconds}s: {operation['command']}\n{limit_text(output, 2000)}")
        if exit_code != 0 and not allow_failure:
            raise RuntimeError(f"Command failed with exit code {exit_code}: {operation['command']}\n{limit_text(output, 2000)}")
        return CommandResult(exit_code=exit_code, output=output)

    def build_adb_args(self, arguments: list[str] | tuple[str, ...]) -> list[str]:
        adb_args: list[str] = []
        if self.resolved_device_serial.strip():
            adb_args.extend(["-s", self.resolved_device_serial])
        adb_args.extend([str(argument) for argument in arguments])
        return adb_args

    def adb(self, arguments: list[str] | tuple[str, ...], *, timeout_seconds: int = 10, allow_failure: bool = False) -> CommandResult:
        if not self.adb_path:
            raise RuntimeError("Harness is not initialized.")
        return self.run_native(
            self.adb_path,
            self.build_adb_args(arguments),
            timeout_seconds=timeout_seconds,
            allow_failure=allow_failure,
        )

    def adb_shell_script(self, script: str, *, timeout_seconds: int = 5, allow_failure: bool = False) -> CommandResult:
        return self.adb(["shell", script], timeout_seconds=timeout_seconds, allow_failure=allow_failure)

    def gradle(self, arguments: list[str] | tuple[str, ...]) -> CommandResult:
        if not self.gradle_wrapper:
            raise RuntimeError("Harness is not initialized.")
        gradle_args = [*arguments, "--stacktrace", "--console=plain"]
        if os.name == "nt":
            command_processor = os.environ.get("COMSPEC") or "cmd.exe"
            return self.run_native(command_processor, ["/c", str(self.gradle_wrapper), *gradle_args])
        if not os.access(self.gradle_wrapper, os.X_OK):
            return self.run_native("bash", [str(self.gradle_wrapper), *gradle_args])
        return self.run_native(self.gradle_wrapper, gradle_args)

    def select_device(self) -> None:
        if not self.adb_path:
            raise RuntimeError("adb is not initialized.")
        result = self.run_native(self.adb_path, ["devices"], timeout_seconds=15, allow_failure=True)
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
        single_room_device_spec = ""
        if self.options.autoplay_mode == "single_room":
            single_room_device_spec = self.ensure_single_room_device_spec()
        args = [
            ":app:stsStart",
            f"-PlaunchMode={self.options.launch_mode}",
            f"-PforceJvmCrash={str(self.options.force_jvm_crash).lower()}",
            f"-PforceRuntimeCrash={str(self.options.force_runtime_crash).lower()}",
            f"-Pautoplay={str(self.options.autoplay).lower()}",
            f"-PautoplaySaveMode={self.options.autoplay_save_mode}",
            f"-PautoplayMode={self.options.autoplay_mode}",
            f"-PautoplaySingleRoomSpec={single_room_device_spec}",
            "-PdisableCardObtainEffectOwnershipCompat="
            + str(self.options.disable_card_obtain_effect_ownership_compat).lower(),
            *self.gradle_device_properties(),
        ]
        self.gradle(args)

    def harness_stop(self) -> None:
        self.gradle([":app:stsStop", *self.gradle_device_properties()])

    def harness_logs(self, output_directory: Path) -> None:
        output_directory.mkdir(parents=True, exist_ok=True)
        self.gradle([":app:stsPullLogs", f"-PlogsDir={output_directory}", *self.gradle_device_properties()])
        archives = sorted(output_directory.glob("sts-jvm-logs-export-*.zip"), key=lambda item: item.stat().st_mtime, reverse=True)
        if archives:
            self.result["artifacts"]["logsZip"] = str(archives[0])

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

    def read_remote_sts_text(
        self,
        sts_root: dict[str, Any],
        relative_path: str,
        tail_lines: int = 0,
        *,
        timeout_seconds: int = 5,
    ) -> str:
        trimmed = relative_path.lstrip("/")
        root_path = str(sts_root["root"])
        remote_path = root_path if not trimmed else f"{root_path}/{trimmed}"
        quoted = quote_android_shell(remote_path)
        script = f"if [ -f {quoted} ]; then tail -n {tail_lines} {quoted}; fi" if tail_lines > 0 else f"if [ -f {quoted} ]; then cat {quoted}; fi"
        if sts_root["accessMode"] == "run-as":
            return self.adb(["exec-out", "run-as", self.application_id or "", "sh", "-c", script], timeout_seconds=timeout_seconds, allow_failure=True).output
        return self.adb_shell_script(script, timeout_seconds=timeout_seconds, allow_failure=True).output

    def remote_sts_root_script(
        self,
        sts_root: dict[str, Any],
        script: str,
        *,
        timeout_seconds: int = 5,
        allow_failure: bool = True,
    ) -> CommandResult:
        if sts_root["accessMode"] == "run-as":
            return self.adb(
                ["exec-out", "run-as", self.application_id or "", "sh", "-c", script],
                timeout_seconds=timeout_seconds,
                allow_failure=allow_failure,
            )
        return self.adb_shell_script(script, timeout_seconds=timeout_seconds, allow_failure=allow_failure)

    def remote_sts_path_state(self, sts_root: dict[str, Any], relative_path: str) -> dict[str, Any]:
        trimmed = relative_path.lstrip("/")
        root_path = str(sts_root["root"])
        remote_path = root_path if not trimmed else f"{root_path}/{trimmed}"
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
        result = self.remote_sts_root_script(sts_root, state_script)
        exists = False
        item_type = None
        bytes_value = None
        mtime_epoch_seconds = None
        for line in result.output.splitlines():
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
        return {
            "relativePath": relative_path,
            "exists": exists,
            "type": item_type,
            "bytes": bytes_value,
            "mtimeEpochSeconds": mtime_epoch_seconds,
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
        package_needles = (
            package_name,
            f"{package_name}:game",
            f"{package_name}:prep",
            f"{package_name}:diag",
            f"Process: {package_name}",
            f">>> {package_name}",
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
            runtime_log_marker = marker_matched in (
                "Game crashed.",
                "Game body patch failed before launch",
                "Exception occurred in CardCrawlGame render method!",
                'Exception in thread "LWJGL Application"',
            )
            if not package_matched and not runtime_log_marker:
                continue
            return {
                "marker": marker_matched,
                "line": line.strip(),
                "packageMatched": package_matched,
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
        prep_pid = self.process_pid_text(f"{package_name}:prep")
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
                "prep": prep_pid,
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

    def requested_mod_tokens(self) -> list[str]:
        tokens: list[str] = []
        for raw in self.options.mods:
            tokens.extend(device_mods.split_mod_tokens(raw))
        if self.options.mod_list_file.strip():
            path = self.resolve_repo_path(self.options.mod_list_file)
            if not path.is_file():
                raise RuntimeError(f"Mod list file not found: {path}")
            tokens.extend(device_mods.read_mod_list_file(path))
            self.result["artifacts"]["modListFile"] = str(path)
        return tokens

    def harness_device_mods(self) -> dict[str, Any]:
        return device_mods.build_device_mod_snapshot(self)

    def harness_set_mods(self) -> dict[str, Any]:
        before = self.harness_device_mods()
        selection = device_mods.resolve_requested_mod_selection(
            before,
            self.requested_mod_tokens(),
            enable_all_mods=self.options.enable_all_mods,
            disable_all_mods=self.options.disable_all_mods,
        )
        device_mods.write_enabled_mod_selection(self, before, selection["selectedStoragePaths"])
        after = self.harness_device_mods()
        return {
            "beforeCounts": before["counts"],
            "selection": selection,
            "after": after,
        }

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
        if command == "doctor":
            status = self.harness_status()
            self.result["statusSnapshot"] = status
            self.set_result_success(True, "OK", "Harness prerequisites are available.")
        elif command == "install":
            self.harness_install()
            self.set_result_success(True, "INSTALLED", "Debug APK installed.")
        elif command == "start":
            self.harness_start()
            self.set_result_success(True, "START_REQUESTED", "Launch request was sent through :app:stsStart.")
        elif command == "stop":
            self.harness_stop()
            self.set_result_success(True, "STOPPED", "Application force-stop completed.")
        elif command == "logs":
            self.harness_logs(resolved_out_dir)
            try:
                logcat_path = self.harness_logcat_dump(resolved_out_dir)
                logcat_text = read_local_text_tail(logcat_path, max_bytes=262144)
                self.result["statusSnapshot"] = self.harness_status(logcat_text, str(logcat_path))
            except Exception as exc:
                self.result["artifacts"]["harnessLogcatError"] = str(exc)
            self.set_result_success(True, "LOGS_EXPORTED", "Log export completed.")
        elif command == "screenshot":
            self.harness_screenshot(resolved_out_dir)
            self.set_result_success(True, "SCREENSHOT_CAPTURED", "Screenshot captured.")
        elif command == "status":
            status = self.harness_status()
            self.result["statusSnapshot"] = status
            self.set_result_success(True, status["observedState"], "Status snapshot captured.")
        elif command == "mods":
            mods = self.harness_device_mods()
            self.result["deviceMods"] = mods
            counts = mods["counts"]
            self.set_result_success(
                True,
                "MODS_LISTED",
                f"Listed {counts['optionalInstalled']} optional mods; {counts['optionalEnabled']} enabled.",
            )
        elif command == "set-mods":
            update = self.harness_set_mods()
            self.result["modSelection"] = {
                "beforeCounts": update["beforeCounts"],
                "selection": update["selection"],
            }
            self.result["deviceMods"] = update["after"]
            counts = update["after"]["counts"]
            self.set_result_success(
                True,
                "MODS_SELECTED",
                f"Selected {counts['optionalEnabled']} of {counts['optionalInstalled']} optional mods.",
            )
        elif command == "smoke":
            return self.run_smoke(resolved_out_dir)
        elif command == "single-room":
            self.options.autoplay = True
            self.options.autoplay_mode = "single_room"
            return self.run_smoke(resolved_out_dir)
        return 0

    def run_smoke(self, resolved_out_dir: Path) -> int:
        status: dict[str, Any] | None = None
        logcat_capture: LogcatCapture | None = None
        logcat_since = ""
        start_requested = False
        try:
            if not self.options.skip_install:
                self.harness_install()
            self.clear_runtime_signals()
            logcat_since = self.device_logcat_timestamp()
            try:
                logcat_capture = self.start_logcat_capture(resolved_out_dir, logcat_since)
            except Exception as exc:
                self.result["artifacts"]["harnessLogcatError"] = str(exc)
            self.harness_start()
            start_requested = True
            status = self.wait_harness_status(logcat_capture)
            self.result["statusSnapshot"] = status
            try:
                self.harness_screenshot(resolved_out_dir)
            except Exception as exc:
                self.result["artifacts"]["screenshotError"] = str(exc)
            try:
                self.harness_logs(resolved_out_dir)
            except Exception as exc:
                self.result["artifacts"]["logsError"] = str(exc)
        finally:
            if not self.options.no_stop_after_smoke and start_requested:
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
                    if self.result.get("statusSnapshot") is None:
                        logcat_text = read_local_text_tail(logcat_path, max_bytes=262144)
                        self.result["statusSnapshot"] = self.harness_status(logcat_text, str(logcat_path))
                    else:
                        self.update_status_harness_logcat(self.result.get("statusSnapshot"), logcat_path)
                except Exception as exc:
                    self.result["artifacts"].setdefault("harnessLogcatError", str(exc))
            status = self.result.get("statusSnapshot")

        if status is None:
            raise RuntimeError("Smoke run did not produce a status snapshot.")
        if self.options.autoplay_mode == "single_room":
            expected_state = "SINGLE_ROOM_COMPLETE"
        else:
            expected_state = "FAIL" if self.options.force_jvm_crash else "CRASH_MARKER" if self.options.force_runtime_crash else "READY"
        if self.options.autoplay_mode == "single_room":
            single_room_result = status.get("latestLog", {}).get("singleRoomResult")
            outcome = single_room_result.get("outcome") if isinstance(single_room_result, dict) else None
            success = status["observedState"] == "SINGLE_ROOM_COMPLETE" and outcome in (
                "monsters_defeated",
                "player_dead",
            )
        elif self.options.force_runtime_crash:
            success = (
                status["observedState"] in ("CRASH_MARKER", "LOGCAT_CRASH")
                or status.get("latestLog", {}).get("crashMarker") is not None
                or (status.get("harnessLogcat") is not None and status["harnessLogcat"].get("crash") is not None)
            )
        else:
            success = status["observedState"] == expected_state
        message = (
            f"Smoke run reached expected state: {expected_state}"
            if success
            else f"Smoke run expected {expected_state} but observed {status['observedState']}"
        )
        if self.options.autoplay_mode == "single_room":
            single_room_result = status.get("latestLog", {}).get("singleRoomResult")
            if isinstance(single_room_result, dict) and single_room_result.get("outcome"):
                detail = single_room_result.get("detail")
                message = (
                    f"Single-room run completed with outcome={single_room_result.get('outcome')}"
                    if success
                    else f"Single-room run failed with outcome={single_room_result.get('outcome')}"
                )
                if detail:
                    message = f"{message} detail={detail}"
        if not success:
            hints = []
            if self.result["artifacts"].get("logsZip"):
                hints.append(f"Logs zip: {self.result['artifacts']['logsZip']}")
            if self.result["artifacts"].get("harnessLogcat"):
                hints.append(f"Harness logcat: {self.result['artifacts']['harnessLogcat']}")
            if hints:
                message = f"{message}. {'; '.join(hints)}"
        self.set_result_success(success, status["observedState"], message)
        return 0 if success else 1

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
            "timeoutSeconds": self.options.timeout_seconds,
            "artifacts": {"outDir": str(resolved_out_dir), "resultJson": str(result_path)},
            "statusSnapshot": None,
            "deviceMods": None,
            "modSelection": None,
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
