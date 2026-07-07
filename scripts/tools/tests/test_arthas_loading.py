from __future__ import annotations

import json
import os
import subprocess
import time
import unittest

from scripts.tools.connector.client import ConnectorClient
from scripts.tools.lib.agent_client import AgentClient
from scripts.tools.lib.env_device import get_test_device_serial


def _start_daemon() -> ConnectorClient:
    proc = subprocess.Popen(
        ["python3", "-m", "scripts.tools.connector.daemon"],
        cwd=os.path.join(os.path.dirname(__file__), "..", "..", ".."),
        stdout=subprocess.PIPE,
        text=True,
    )
    info = json.loads(proc.stdout.readline().strip())
    time.sleep(0.3)
    client = ConnectorClient(port=info["port"], token=info["token"])
    client.connect()
    client._daemon_proc = proc
    return client


def _stop_daemon(conn: ConnectorClient) -> None:
    try:
        conn.send_request({"method": "quit"})
    except Exception:
        pass
    conn.close()
    if hasattr(conn, "_daemon_proc"):
        conn._daemon_proc.wait(timeout=5)


class TestArthasIsolatedLoading(unittest.TestCase):
    """Verify Arthas loads without 'already bind' error."""

    def test_arthas_starts_without_already_bind_error(self):
        conn = _start_daemon()
        try:
            conn.select(get_test_device_serial())

            for _ in range(30):
                out = conn.shell("pidof io.stamethyst")
                pid = out.get("stdout", "").strip()
                if pid:
                    t = conn.shell(f"ls /proc/{pid}/task | wc -l")
                    threads = int(t.get("stdout", "0").strip())
                    if threads > 50:
                        break
                time.sleep(5)

            agent = AgentClient(connector=conn, port=9099)
            agent.connect()

            r1 = agent.send(
                "LOAD_AGENT /data/data/io.stamethyst/files/arthas-agent.jar "
                "/data/data/io.stamethyst/files/arthas-core.jar"
            )
            self.assertEqual(r1, "OK", f"agent load failed: {r1}")

            time.sleep(5)
            logs = conn.shell(
                "cat /sdcard/Android/data/io.stamethyst/files/sts/home/logs/arthas/"
                "arthas.log 2>/dev/null"
            )
            log_text = logs.get("stdout", "")

            self.assertNotIn("already bind", log_text, "Spy was pre-loaded by system classloader")

            agent.close()
        finally:
            _stop_daemon(conn)
