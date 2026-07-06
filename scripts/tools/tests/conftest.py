"""
Shared test fixtures — import from tests/.
"""
from __future__ import annotations

import os
import subprocess
import time


def connector_daemon_fixture(socket_name: str) -> subprocess.Popen:
    sock_path = f"/tmp/{socket_name}-{os.getpid()}.sock"
    try:
        os.unlink(sock_path)
    except OSError:
        pass
    proc = subprocess.Popen(
        ["python3", "-m", "scripts.tools.connector.daemon",
         "--socket", sock_path],
        cwd=os.path.join(os.path.dirname(__file__), "..", "..", ".."),
    )
    time.sleep(1)
    return proc


def cleanup_fixture(proc: subprocess.Popen, sock_path: str) -> None:
    if proc:
        proc.terminate()
        proc.wait(timeout=5)
    try:
        os.unlink(sock_path)
    except OSError:
        pass
