from __future__ import annotations

import json
import os
import subprocess
import time
import unittest

from scripts.tools.connector.client import ConnectorClient
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
    client = ConnectorClient(port=info["port"])
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


class TestConnectStreamIntegration(unittest.TestCase):

    def test_connect_stream_handshake(self):
        """Verify daemon responds to connect_stream with stream_id."""
        if not get_test_device_serial() or get_test_device_serial() == "auto":
            self.skipTest("set STS_TEST_DEVICE to run real-device stream integration")
        conn = _start_daemon()
        try:
            conn.select(get_test_device_serial())

            resp = conn.send_request({
                "method": "connect_stream",
                "params": {"port": 19099},
            })
            self.assertIn("stream_id", resp)
        finally:
            _stop_daemon(conn)

    def test_passthrough_game_probe_list(self):
        if not get_test_device_serial() or get_test_device_serial() == "auto":
            self.skipTest("set STS_TEST_DEVICE to run real-device stream integration")
        """connect_stream to game-probe :9099 and send LIST."""
        conn = _start_daemon()
        try:
            conn.select(get_test_device_serial())

            stream = conn.connect_stream(port=9099)
            stream.write(b"LIST\n")
            time.sleep(1)
            data = stream.read(4096)
            self.assertIn(b"MONITORS", data, f"Expected MONITORS, got: {data}")
            stream.close()
        finally:
            _stop_daemon(conn)


if __name__ == "__main__":
    unittest.main()
