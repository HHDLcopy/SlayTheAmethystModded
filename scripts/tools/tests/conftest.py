"""
Shared test fixtures — import from tests/.
"""
from __future__ import annotations

import json
import os
import subprocess
import time


def connector_daemon_fixture() -> tuple[subprocess.Popen, dict[str, str]]:
    proc = subprocess.Popen(
        ["python3", "-m", "scripts.tools.connector.daemon"],
        cwd=os.path.join(os.path.dirname(__file__), "..", "..", ".."),
        stdout=subprocess.PIPE,
        text=True,
    )
    started = json.loads(proc.stdout.readline().strip())
    time.sleep(0.3)
    return proc, started


def cleanup_fixture(proc: subprocess.Popen) -> None:
    if proc:
        proc.terminate()
        proc.wait(timeout=5)
