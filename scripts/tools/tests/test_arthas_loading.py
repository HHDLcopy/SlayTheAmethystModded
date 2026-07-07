from __future__ import annotations

import os
import subprocess
import time
import unittest

from scripts.tools.connector.client import ConnectorClient
from scripts.tools.lib.agent_client import AgentClient
from scripts.tools.lib.env_device import get_test_device_serial


class TestArthasIsolatedLoading(unittest.TestCase):
    """Verify Arthas loads without 'already bind' error."""

    @classmethod
    def setUpClass(cls):
        cls._sock_path = "/tmp/sts-arthas-test.sock"
        try:
            os.unlink(cls._sock_path)
        except OSError:
            pass
        cls._daemon = subprocess.Popen(
            ["python3", "-m", "scripts.tools.connector.daemon",
             "--socket", cls._sock_path],
            cwd=os.path.join(os.path.dirname(__file__), "..", "..", ".."),
        )
        time.sleep(1)

    @classmethod
    def tearDownClass(cls):
        if cls._daemon:
            cls._daemon.terminate()
            cls._daemon.wait(timeout=5)

    def test_arthas_starts_without_already_bind_error(self):
        conn = ConnectorClient(self._sock_path)
        conn.connect()
        conn.select(get_test_device_serial())

        # Wait for JVM
        for _ in range(30):
            out = conn.shell("pidof io.stamethyst")
            pid = out.get("stdout", "").strip()
            if pid:
                t = conn.shell(f"ls /proc/{pid}/task | wc -l")
                threads = int(t.get("stdout", "0").strip())
                if threads > 50:
                    break
            time.sleep(5)

        conn.forward(port=9099)
        agent = AgentClient(port=9099)
        agent.connect()

        # Load only agent JAR with isolated ClassLoader
        # Args: <core-path>;<key=val;...>  — but we test with core only
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
        conn.unforward(port=9099)
        conn.close()
