"""Stage 0 — Environment check.

Verifies the game process is alive, boot bridge reports READY,
and the agent-connector TCP server is accepting connections.
"""

from __future__ import annotations

import json
import subprocess
from typing import TYPE_CHECKING

from .base import Stage

if TYPE_CHECKING:
    from ..demo_runner import DemoRunner


class SetupStage(Stage):
    id = "setup"
    name = "Environment Check"

    def run(self, runner: DemoRunner, out_dir: str) -> dict:
        adb = [str(runner.adb_path), "-s", runner.resolved_device_serial]
        pkg = "io.stamethyst"

        checks: dict[str, bool] = {}

        # 1. game process
        result = subprocess.run(
            adb + ["shell", f"pidof {pkg}:game"],
            capture_output=True, text=True, timeout=10,
        )
        game_pid = result.stdout.strip()
        checks["game_process"] = bool(game_pid)
        runner._log_op(
            f"pidof {pkg}:game",
            f"pid={game_pid}" if game_pid else "not running",
        )

        # 2. boot bridge
        result = subprocess.run(
            adb + ["shell", "cat /sdcard/Android/data/io.stamethyst/files/sts/boot_bridge_events.log"],
            capture_output=True, text=True, timeout=10,
        )
        boot_events = result.stdout.strip()
        checks["boot_bridge_ready"] = "READY" in boot_events
        runner._log_op("cat boot_bridge_events.log", boot_events[-500:] if boot_events else "empty")

        # 3. agent TCP
        resp = runner.conn.send_command("LIST")
        checks["agent_server"] = resp.startswith("MONITORS")
        runner._log_op("TCP: LIST", resp)

        # 4. screenshot baseline
        png_path = f"{out_dir}/screen-baseline.png"
        subprocess.run(
            adb + ["shell", "screencap", "-p", "/sdcard/demo_setup.png"],
            capture_output=True, timeout=10,
        )
        subprocess.run(
            adb + ["pull", "/sdcard/demo_setup.png", png_path],
            capture_output=True, timeout=10,
        )
        runner._log_op("screencap → screen-baseline.png", png_path)

        all_ok = all(checks.values())
        return {
            "success": all_ok,
            "status": "READY" if all_ok else "INCOMPLETE",
            "message": (
                f"game={checks.get('game_process')} "
                f"boot_bridge={checks.get('boot_bridge_ready')} "
                f"agent={checks.get('agent_server')}"
            ),
            "data": {"checks": checks},
        }
