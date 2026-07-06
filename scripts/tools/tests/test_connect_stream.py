from __future__ import annotations

import os
import subprocess
import time
import unittest

from scripts.tools.connector.client import ConnectorClient


class TestConnectStreamIntegration(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls._sock_path = f"/tmp/sts-stream-test-{os.getpid()}.sock"
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

    def test_connect_stream_handshake(self):
        """Verify daemon responds to connect_stream with stream_id."""
        conn = ConnectorClient(self._sock_path)
        conn.connect()
        conn.select("localhost:15555")

        resp = conn.send_request({
            "method": "connect_stream",
            "params": {"port": 19099},
        })
        self.assertIn("stream_id", resp)
        conn.close()

    def test_passthrough_game_probe_list(self):
        """connect_stream to game-probe :9099 and send LIST."""
        conn = ConnectorClient(self._sock_path)
        conn.connect()
        conn.select("localhost:15555")

        stream = conn.connect_stream(port=9099)
        stream.write(b"LIST\n")
        time.sleep(1)
        data = stream.read(4096)
        self.assertIn(b"MONITORS", data, f"Expected MONITORS, got: {data}")
        stream.close()


if __name__ == "__main__":
    unittest.main()
