import json
import os
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
    debug_mode: bool
    autoplay: bool
    skip_install: bool
    no_stop_after_smoke: bool
    mods: list[str]
    mod_list_file: str
    enable_all_mods: bool
    disable_all_mods: bool
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
    agent_port: int = 9090
    agent_duration: float = 0.0
    redefine_class_file: str = ""
    cache_hit_runs: int = 1
    no_clear_startup_cache: bool = False


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
        ctx = self._build_context()

        if command in ("doctor", "install", "start", "stop", "logs", "screenshot", "status",
                       "mods", "set-mods", "smoke", "decompil", "agent-attach", "agent-detach",
                       "agent-list", "agent-status", "play", "hotreload", "perf", "single-room",
                       "startup-cache-profile"):
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
