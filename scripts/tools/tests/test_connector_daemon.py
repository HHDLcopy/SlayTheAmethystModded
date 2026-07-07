from __future__ import annotations

import json
import os
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from scripts.tools.connector.client import ConnectorClient
from scripts.tools.lib.env_device import get_test_device_serial


class TestConnectorDaemonIntegration(unittest.TestCase):

    def test_ping_pong_over_real_socket(self):
        import socket as _socket
        import threading
        import time

        sock_path = f"/tmp/sts-connector-test-{os.getpid()}.sock"
        try:
            os.unlink(sock_path)
        except OSError:
            pass

        server = _socket.socket(_socket.AF_UNIX, _socket.SOCK_STREAM)
        server.bind(sock_path)
        server.listen(1)
        server.settimeout(5)

        ready = threading.Event()
        requests: list[dict] = []

        def handle():
            server.settimeout(5)
            ready.set()
            try:
                conn, _ = server.accept()
                conn.settimeout(5)
                reader = conn.makefile("r", encoding="utf-8", newline="\n")
                line = reader.readline().strip()
                req = json.loads(line)
                requests.append(req)
                resp = json.dumps({"pong": True}) + "\n"
                conn.sendall(resp.encode("utf-8"))
                conn.close()
            except Exception:
                pass

        t = threading.Thread(target=handle, daemon=True)
        t.start()
        ready.wait(timeout=3)

        client = ConnectorClient(socket_path=sock_path)
        client.connect()
        response = client.send_request({"method": "ping"})
        client.close()

        t.join(timeout=3)
        server.close()
        try:
            os.unlink(sock_path)
        except OSError:
            pass

        self.assertEqual(response, {"pong": True})
        self.assertEqual(requests, [{"method": "ping"}])

    def test_daemon_devices_integration(self):
        import subprocess
        import time

        sock_path = f"/tmp/sts-connector-test-{os.getpid()}.sock"
        try:
            os.unlink(sock_path)
        except OSError:
            pass

        proc = subprocess.Popen(
            [
                "python3", "-m", "scripts.tools.connector.daemon",
                "--socket", sock_path,
            ],
            cwd=os.path.join(os.path.dirname(__file__), "..", "..", ".."),
        )

        time.sleep(1)

        client = ConnectorClient(socket_path=sock_path)
        client.connect()
        response = client.send_request({"method": "ping"})
        self.assertEqual(response, {"pong": True})
        client.close()

        proc.terminate()
        proc.wait(timeout=5)
        try:
            os.unlink(sock_path)
        except OSError:
            pass

    def test_daemon_real_device_flow(self):
        import subprocess
        import time

        sock_path = f"/tmp/sts-connector-test-{os.getpid()}.sock"
        try:
            os.unlink(sock_path)
        except OSError:
            pass

        proc = subprocess.Popen(
            [
                "python3", "-m", "scripts.tools.connector.daemon",
                "--socket", sock_path,
            ],
            cwd=os.path.join(os.path.dirname(__file__), "..", "..", ".."),
        )

        time.sleep(1)

        client = ConnectorClient(socket_path=sock_path)
        client.connect()

        # 1. ping
        resp = client.send_request({"method": "ping"})
        self.assertEqual(resp, {"pong": True})

        # 2. devices
        devices = client.devices()
        self.assertIsInstance(devices, list)
        serials = [d["serial"] for d in devices]
        expected_device = get_test_device_serial()
        if expected_device == "auto":
            expected_device = serials[0]
        self.assertIn(expected_device, serials)

        # 3. select
        ok = client.select(expected_device, timeout_ms=10000)
        self.assertTrue(ok)

        # 4. status
        status = client.status()
        self.assertIn("serial", status)

        # 5. shell
        result = client.shell("echo integration-test-ok")
        self.assertEqual(result["exit"], 0)
        self.assertIn("integration-test-ok", result["stdout"])

        # 6. forward / unforward
        fw = client.forward(port=9099)
        self.assertTrue(fw.get("ok"))
        un = client.unforward(port=9099)
        self.assertTrue(un)

        # 7. push / pull
        import tempfile
        with tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False) as f:
            f.write("push-test-content\n")
            local_push = f.name
        local_pull = f"{local_push}.pulled"
        resp = client.send_request({
            "method": "push",
            "params": {
                "local": local_push,
                "remote": "/sdcard/sts-daemon-push-test.txt",
            },
        })
        self.assertTrue(resp.get("ok"))
        resp = client.send_request({
            "method": "pull",
            "params": {
                "remote": "/sdcard/sts-daemon-push-test.txt",
                "local": local_pull,
            },
        })
        self.assertTrue(resp.get("ok"))
        pulled = open(local_pull).read()
        self.assertEqual(pulled, "push-test-content\n")
        os.unlink(local_push)
        os.unlink(local_pull)
        client.shell("rm -f /sdcard/sts-daemon-push-test.txt")

        client.close()
        proc.terminate()
        proc.wait(timeout=5)
        try:
            os.unlink(sock_path)
        except OSError:
            pass


if __name__ == "__main__":
    unittest.main()
