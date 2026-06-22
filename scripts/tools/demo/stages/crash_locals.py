"""Stage 5 — Crash locals capture.

Reads the premain auto-attach log (`agent_premain.jsonl`) from the
device and formats captured local variables from any method_exception
events.
"""

from __future__ import annotations

import json
import subprocess
from typing import TYPE_CHECKING

from .base import Stage

if TYPE_CHECKING:
    from ..demo_runner import DemoRunner


AGENT_LOG_REMOTE = (
    "/sdcard/Android/data/io.stamethyst/files/sts/agent_premain.jsonl"
)


class CrashLocalsStage(Stage):
    id = "crash_locals"
    name = "Crash Locals Capture"

    def run(self, runner: DemoRunner, out_dir: str) -> dict:
        adb = [str(runner.adb_path), "-s", runner.resolved_device_serial]

        # Pull agent log from device
        local_path = f"{out_dir}/agent_premain.jsonl"
        result = subprocess.run(
            adb + ["pull", AGENT_LOG_REMOTE, local_path],
            capture_output=True, text=True, timeout=10,
        )
        runner._log_op(f"pull {AGENT_LOG_REMOTE}", result.stdout.strip())

        # Parse events
        events: list[dict] = []
        try:
            with open(local_path, encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        evt = json.loads(line)
                        events.append(evt)
                    except json.JSONDecodeError:
                        pass
        except FileNotFoundError:
            pass

        # Find method_exception events
        exceptions = [e for e in events if e.get("type") == "method_exception"]
        entries = [e for e in events if e.get("type") == "method_entry"]
        exits = [e for e in events if e.get("type") == "method_exit"]

        # Extract locals from the most recent exception
        captured_locals: dict[str, str] = {}
        exception_type = ""
        if exceptions:
            latest = exceptions[-1]
            exception_type = latest.get("exception_type", "")
            captured_locals = latest.get("locals", {})

        summary = {
            "total_events": len(events),
            "method_entries": len(entries),
            "method_exits": len(exits),
            "method_exceptions": len(exceptions),
            "exception_type": exception_type,
            "locals_count": len(captured_locals),
            "locals": captured_locals,
        }
        with open(f"{out_dir}/crash-summary.json", "w", encoding="utf-8") as f:
            json.dump(summary, f, indent=2)

        ok = len(exceptions) > 0
        return {
            "success": ok,
            "status": f"{len(exceptions)} crashes captured" if ok else "NO_CRASH",
            "message": (
                f"Events: {len(entries)} entries / {len(exits)} exits / "
                f"{len(exceptions)} exceptions | "
                f"Locals captured: {', '.join(captured_locals.keys())}"
            ),
            "data": summary,
        }
