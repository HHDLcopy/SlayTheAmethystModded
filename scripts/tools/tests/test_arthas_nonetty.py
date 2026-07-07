from __future__ import annotations

import json
import os
import subprocess
import time
import unittest

from scripts.tools.connector.client import ConnectorClient
from scripts.tools.lib.agent_client import AgentClient
from scripts.tools.arthas.shell import ArthasShell
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


class TestModifiedArthasCommands(unittest.TestCase):

    def test_version_command_returns_non_null(self):
        """Load bridge via modified Arthas, send 'version', expect non-null output."""
        conn = _start_daemon()
        try:
            conn.select(get_test_device_serial())

            for _ in range(30):
                out = conn.shell("pidof io.stamethyst")
                pid = out.get("stdout", "").strip()
                if pid:
                    t = conn.shell(f"ls /proc/{pid}/task | wc -l")
                    if int(t.get("stdout", "0").strip()) > 50:
                        break
                time.sleep(5)

            agent = AgentClient(connector=conn, port=9099)
            agent.connect()

            agent.send("LOAD_AGENT /data/data/io.stamethyst/files/arthas/arthas-core.jar")
            resp = agent.send(
                "LOAD_AGENT /data/data/io.stamethyst/files/arthas/arthas-bridge.jar "
                "port=8099"
            )
            self.assertEqual(resp, "OK", f"bridge load failed: {resp}")

            time.sleep(5)

            stream = conn.connect_stream(port=8099)
            shell = ArthasShell(stream=stream)
            result = shell.command("version")

            self.assertTrue(result and "3.6" in result,
                            f"Command returned unexpected: {repr(result)}")

            stream.close()
            agent.close()
        finally:
            _stop_daemon(conn)
