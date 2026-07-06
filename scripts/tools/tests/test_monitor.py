from __future__ import annotations

import os
import subprocess
import tempfile
import time
import unittest

from scripts.tools.connector.client import ConnectorClient


class TestDeviceMonitor(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls._daemon_proc = None
        cls._sock_path = f"/tmp/sts-monitor-test-{os.getpid()}.sock"
        try:
            os.unlink(cls._sock_path)
        except OSError:
            pass
        cls._daemon_proc = subprocess.Popen(
            [
                "python3", "-m", "scripts.tools.connector.daemon",
                "--socket", cls._sock_path,
            ],
            cwd=os.path.join(os.path.dirname(__file__), "..", "..", ".."),
        )
        time.sleep(1)

    @classmethod
    def tearDownClass(cls):
        if cls._daemon_proc:
            cls._daemon_proc.terminate()
            cls._daemon_proc.wait(timeout=5)
        try:
            os.unlink(cls._sock_path)
        except OSError:
            pass

    def setUp(self):
        client = ConnectorClient(socket_path=self._sock_path)
        client.connect()
        client.select("localhost:15555", timeout_ms=10000)
        self.client = client

    def tearDown(self):
        self.client.close()

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
