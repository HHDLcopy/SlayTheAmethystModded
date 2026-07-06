from __future__ import annotations

import os
import subprocess
import time
import unittest

from scripts.tools.connector.client import ConnectorClient
from scripts.tools.lib.agent_client import AgentClient


class TestModifiedArthasCommands(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls._sock_path = "/tmp/sts-nonetty-test.sock"
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
        try:
            os.unlink(cls._sock_path)
        except OSError:
            pass

    def test_version_command_returns_non_null(self):
        """Load bridge via modified Arthas, send 'version', expect non-null output."""
        conn = ConnectorClient(self._sock_path)
        conn.connect()
        conn.select("localhost:15555")

        # Wait for JVM and connect agent
        for _ in range(30):
            out = conn.shell("pidof io.stamethyst")
            pid = out.get("stdout", "").strip()
            if pid:
                t = conn.shell(f"ls /proc/{pid}/task | wc -l")
                if int(t.get("stdout", "0").strip()) > 50:
                    break
            time.sleep(5)

        conn.forward(port=9099)
        agent = AgentClient(port=9099)
        agent.connect()

        # Load core, then bridge (which internally uses modified ArthasBootstrap)
        agent.send("LOAD_AGENT /data/data/io.stamethyst/files/arthas/arthas-core.jar")
        resp = agent.send(
            "LOAD_AGENT /data/data/io.stamethyst/files/arthas/arthas-bridge.jar "
            "port=8099"
        )
        self.assertEqual(resp, "OK", f"bridge load failed: {resp}")

        time.sleep(5)

        # Connect to bridge and test version command
        conn.forward(port=8099)
        import socket
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(10)
        s.connect(("127.0.0.1", 8099))
        time.sleep(1)
        s.recv(4096)  # consume prompt

        s.sendall(b"version\n")
        time.sleep(6)
        data = s.recv(16384).decode("utf-8", errors="replace")

        # Must NOT contain "null\n[arthas@"
        self.assertNotIn("null\n", data,
                         f"Command returned null: {repr(data)}")

        s.close()
        agent.close()
        conn.unforward(port=9099)
        conn.unforward(port=8099)
        conn.close()
