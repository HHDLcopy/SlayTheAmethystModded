from __future__ import annotations

import json
import os
import subprocess
import tempfile
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


class TestDeviceMonitor(unittest.TestCase):

    def setUp(self):
        client = _start_daemon()
        client.select(get_test_device_serial(), timeout_ms=10000)
        self.client = client

    def tearDown(self):
        _stop_daemon(self.client)

    def test_screenshot_captures_png(self):
        from scripts.tools.monitor.monitor import DeviceMonitor
        with tempfile.TemporaryDirectory() as tmpdir:
            out = os.path.join(tmpdir, "screen.png")
            monitor = DeviceMonitor(connector_client=self.client)
            monitor.screenshot(out)
            self.assertTrue(os.path.isfile(out))
            size = os.path.getsize(out)
            self.assertGreater(size, 100,
                f"screenshot file too small: {size} bytes")

    def test_pull_file_from_device(self):
        from scripts.tools.monitor.monitor import DeviceMonitor
        monitor = DeviceMonitor(connector_client=self.client)
        self.client.shell(
            "echo test-content > /sdcard/sts-monitor-test.txt")
        with tempfile.TemporaryDirectory() as tmpdir:
            out = os.path.join(tmpdir, "pulled.txt")
            monitor.pull(
                remote="/sdcard/sts-monitor-test.txt", local=out)
            self.assertTrue(os.path.isfile(out))
            content = open(out).read().strip()
            self.assertEqual(content, "test-content")
        self.client.shell("rm -f /sdcard/sts-monitor-test.txt")


if __name__ == "__main__":
    unittest.main()
