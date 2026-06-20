"""Demo runner — top-level stage executor.

Mirrors Harness.run() / HarnessOptions pattern:
  - DemoOptions holds flat configuration.
  - DemoRunner owns the connection lifecycle and stage dispatch.
  - Each stage gets its own output subdirectory under the out_dir.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

# Add parent (scripts/tools/) to sys.path so 'lib' is importable
_sys_path_parent = str(Path(__file__).resolve().parents[1])
if _sys_path_parent not in sys.path:
    sys.path.insert(0, _sys_path_parent)

from lib.agent_bridge import AgentBridge, AgentBridgeError
from lib.agent_protocol import AgentProtocol
from lib.harness_connection import HarnessConnection
from lib.sts_harness import (
    CommandResult,
    file_timestamp,
    read_local_property,
    repo_root,
    utc_timestamp,
)

from .stages.base import Stage
from .stages.setup import SetupStage
from .stages.observe import ObserveStage
from .stages.play import PlayStage
from .stages.perf import PerfStage
from .stages.hotreload import HotreloadStage
from .stages.crash_locals import CrashLocalsStage


ALL_STAGES: dict[str, Stage] = {
    "setup":        SetupStage(),
    "hotreload":    HotreloadStage(),
    "observe":      ObserveStage(),
    "play":         PlayStage(),
    "perf":         PerfStage(),
    "crash_locals": CrashLocalsStage(),
}


@dataclass
class DemoOptions:
    """Mirrors HarnessOptions — flat, serialisable configuration."""
    device_serial: str = ""
    agent_port: int = 9099
    out_dir: str = ""
    stages: tuple = ("all",)
    resume: bool = False
    no_cfr: bool = False
    install_test_crash: bool = False


class DemoRunner:
    """Top-level demo executor.  Mirrors Harness.run() pattern."""

    def __init__(self, options: DemoOptions) -> None:
        self.options = options
        self.repo_root = repo_root()
        self.config_root = self.repo_root / "scripts" / "tools" / "lib"
        self.adb_path: str | None = None
        self.application_id: str | None = None
        self.resolved_device_serial = options.device_serial.strip()
        self.operations: list[dict[str, Any]] = []
        self.started_at = datetime.now(timezone.utc)
        self.conn: HarnessConnection | None = None
        self._proto: AgentProtocol | None = None
        self._bridge: AgentBridge | None = None
        self._stages: dict[str, Stage] = {}
        self._stage_results: dict[str, dict] = {}

    # ── Stage selection ────────────────────────────────────────────

    def _selected_stage_ids(self) -> list[str]:
        if "all" in self.options.stages:
            return list(ALL_STAGES.keys())
        selected = []
        for token in self.options.stages:
            for s in token.split(","):
                s = s.strip()
                if s and s in ALL_STAGES:
                    if not self.options.resume or s != "setup":
                        selected.append(s)
        return selected

    # ── Initialisation ─────────────────────────────────────────────

    def testcrash_install(self) -> bool:
        """Push TestCrashCard.jar to the device and enable it in mods_library.
        Must run BEFORE the game starts (the game reads mods at boot).
        Returns True on success.
        """
        jar_path = self.repo_root / "scripts" / "tools" / "demo" / "testcrash" / "TestCrashCard.jar"
        if not jar_path.is_file():
            print(f"TestCrashCard.jar not found at {jar_path}")
            return False

        mod_dir = "/sdcard/Android/data/io.stamethyst/files/sts/mods_library"
        remote_jar = f"{mod_dir}/TestCrashCard.jar"
        config = "/sdcard/Android/data/io.stamethyst/files/sts/enabled_mods.txt"
        self.adb_path = self.resolve_adb_path()
        self.resolved_device_serial = self.resolved_device_serial or "localhost:15555"

        print(f"Pushing TestCrashCard.jar → {remote_jar}")
        self._adb_runner(["shell", f"mkdir -p {mod_dir}"])
        self._adb_runner(["push", str(jar_path), remote_jar])

        # Enable the mod
        index_remote = "/sdcard/Android/data/io.stamethyst/files/sts/optional_mod_index.json"
        filelist_remote = "/sdcard/Android/data/io.stamethyst/files/sts/.mts_mod_file_list"
        self._adb_runner(["shell", f"rm -f {index_remote} {filelist_remote}"])
        self._adb_runner(["shell", f"echo '{remote_jar}' > {config}"])

        # Verify
        import subprocess as sp
        args = [str(self.adb_path), "-s", self.resolved_device_serial, "shell", f"cat {config}"]
        r = sp.run(args, capture_output=True, text=True, timeout=10)
        result = r.stdout.strip()
        ok = "TestCrashCard" in result
        print(f"enabled_mods.txt: {result}" + ("  ✅" if ok else "  ❌"))
        self._log_op("testcrash_install",
            f"push={remote_jar} enabled={'OK' if ok else 'FAILED'}")
        return ok

    def resolve_adb_path(self) -> str:
        """Copy of Harness.resolve_adb_path()."""
        adb_name = "adb.exe" if sys.platform == "win32" else "adb"
        local_sdk = read_local_property(self.repo_root / "local.properties", "sdk.dir")
        if local_sdk and not Path(local_sdk).is_absolute():
            local_sdk = str((self.repo_root / local_sdk).resolve())
        candidates = [
            __import__("os").environ.get("ANDROID_SDK_ROOT", ""),
            __import__("os").environ.get("ANDROID_HOME", ""),
            local_sdk,
        ]
        for sdk in [c for c in candidates if c and c.strip()]:
            adb = Path(sdk).expanduser().resolve() / "platform-tools" / adb_name
            if adb.exists():
                return str(adb)
        adb = shutil.which("adb")
        if adb:
            return adb
        raise RuntimeError(
            "Could not resolve adb. "
            "Set sdk.dir, ANDROID_SDK_ROOT, ANDROID_HOME, or add adb to PATH."
        )

    def _adb_runner(self, arguments: list[str]) -> None:
        """Thin adb caller that logs to operations[]."""
        import subprocess as sp
        args = [str(self.adb_path), "-s", self.resolved_device_serial, *arguments]
        started = datetime.now(timezone.utc)
        try:
            r = sp.run(args, capture_output=True, text=True, timeout=30)
            out = r.stdout + r.stderr
            ok = r.returncode == 0
        except Exception as e:
            out = str(e)
            ok = False
        ended = datetime.now(timezone.utc)
        self.operations.append({
            "command": " ".join(args),
            "exitCode": 0 if ok else 1,
            "startedAt": utc_timestamp(started),
            "endedAt": utc_timestamp(ended),
            "durationMs": int((ended - started).total_seconds() * 1000),
            "timedOut": False,
            "outputTail": out[-2000:] if out else "",
        })

    def _log_op(self, command: str, response: str) -> None:
        now = utc_timestamp()
        self.operations.append({
            "command": command,
            "exitCode": 0,
            "startedAt": now,
            "endedAt": now,
            "durationMs": 0,
            "timedOut": False,
            "outputTail": response[:500],
        })

    def initialize(self) -> None:
        self.adb_path = self.resolve_adb_path()
        self.resolved_device_serial = self.resolved_device_serial or "localhost:15555"

        self.conn = HarnessConnection(
            adb_runner=self._adb_runner,
            port=self.options.agent_port,
        )
        self.conn.setup_forward()
        self._log_op(
            f"adb -s {self.resolved_device_serial} forward tcp:{self.options.agent_port} tcp:{self.options.agent_port}",
            "OK",
        )
        self.conn.connect()
        self._log_op(f"TCP connect 127.0.0.1:{self.options.agent_port}", "OK")
        self._proto = AgentProtocol(self.conn)
        self._bridge = AgentBridge(port=self.options.agent_port, connection=self.conn)

    def shutdown(self) -> None:
        if self.conn:
            try:
                self.conn.close()
                self.conn.remove_forward()
            except Exception:
                pass

    # ── Main entry ─────────────────────────────────────────────────

    def run(self) -> int:
        """Execute selected stages.  Returns 0 on success."""
        # ── Pre-flight: install TestCrashCard mod if requested ───
        if self.options.install_test_crash:
            if not self.testcrash_install():
                return 1
            print("TestCrashCard mod installed. Now run: python scripts/tools/main.py sts-harness -Command smoke -LaunchMode mts -Autoplay ...")
            return 0

        try:
            self.initialize()
        except Exception as e:
            self._stage_results["init"] = {
                "success": False,
                "status": "CONNECTION_FAILED",
                "message": str(e),
            }
            self._write_report()
            return 1

        stage_ids = self._selected_stage_ids()
        if not stage_ids:
            self._stage_results["init"] = {
                "success": True,
                "status": "NO_STAGES_SELECTED",
                "message": "No stages matched the --stages filter.",
            }
            self._write_report()
            self.shutdown()
            return 0

        out_root = self._resolve_out_dir()
        self._out_root = out_root

        for sid in stage_ids:
            stage = ALL_STAGES[sid]
            stage_dir = out_root / f"stage-{stage.id}"
            stage_dir.mkdir(parents=True, exist_ok=True)
            result = self._run_single_stage(stage, stage_dir)
            self._stage_results[sid] = result

        self._write_report()
        self.shutdown()
        return 0 if all(r.get("success", False) for r in self._stage_results.values()) else 1

    def _run_single_stage(self, stage: Stage, out_dir: Path) -> dict:
        t0 = time.monotonic()
        try:
            result = stage.run(self, str(out_dir))
        except AgentBridgeError as e:
            result = {"success": False, "status": "ERROR", "message": str(e)}
        except Exception as e:
            result = {"success": False, "status": "ERROR", "message": f"{type(e).__name__}: {e}"}
        elapsed = int((time.monotonic() - t0) * 1000)
        result.setdefault("durationMs", elapsed)

        verified = stage.verify(result)
        result["verified"] = verified
        return result

    # ── Output ─────────────────────────────────────────────────────

    def _resolve_out_dir(self) -> Path:
        if self.options.out_dir.strip():
            return Path(self.options.out_dir)
        return self.repo_root / "demo-artifacts" / file_timestamp()

    def _write_report(self) -> None:
        out_root = getattr(self, "_out_root", None) or self._resolve_out_dir()
        out_root.mkdir(parents=True, exist_ok=True)
        ended = datetime.now(timezone.utc)

        report = {
            "schemaVersion": 1,
            "tool": "demo",
            "startedAt": utc_timestamp(self.started_at),
            "endedAt": utc_timestamp(ended),
            "durationMs": int((ended - self.started_at).total_seconds() * 1000),
            "options": {
                "stages": list(self.options.stages),
                "deviceSerial": self.resolved_device_serial,
                "agentPort": self.options.agent_port,
                "resume": self.options.resume,
                "noCFR": self.options.no_cfr,
            },
            "result": self._stage_results,
            "operations": self.operations,
        }

        json_path = out_root / "report.json"
        json_path.write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

        # Also write a summary line to stdout
        passed = sum(1 for r in self._stage_results.values() if r.get("success"))
        total = len(self._stage_results)
        status_line = "=" * 60
        print(f"\n{status_line}")
        for sid, r in self._stage_results.items():
            icon = "✅" if r.get("success") else "❌"
            print(f"  {icon} {sid:12s}  {r.get('status', '?')}")
        print(f"{status_line}")
        print(f"  {passed}/{total} stages passed")
        print(f"  Report: {json_path}")
