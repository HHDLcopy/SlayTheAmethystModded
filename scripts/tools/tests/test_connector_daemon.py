from __future__ import annotations

import json
import os
import secrets
import socket as _socket
import subprocess
import threading
import time
import unittest

from scripts.tools.connector.client import ConnectorClient
from scripts.tools.lib.env_device import get_test_device_serial


def _start_fake_connector_daemon(token: str) -> tuple[_socket.socket, int]:
    """Start a minimal TCP server that mimics the connector auth protocol."""
    server = _socket.socket(_socket.AF_INET, _socket.SOCK_STREAM)
    server.setsockopt(_socket.SOL_SOCKET, _socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", 0))
    server.listen(1)
    port = server.getsockname()[1]

    def handle():
        server.settimeout(5)
        try:
            conn, _ = server.accept()
            conn.settimeout(5)
            reader = conn.makefile("r", encoding="utf-8", newline="\n")
            auth = reader.readline().strip()
            if auth != f"AUTH {token}":
                conn.sendall(b'{"error":{"code":-32005,"message":"auth failed"}}\n')
                conn.close()
                return
            line = reader.readline().strip()
            req = json.loads(line)
            resp = json.dumps({"pong": True}) + "\n"
            conn.sendall(resp.encode("utf-8"))
            conn.close()
        except Exception:
            pass

    t = threading.Thread(target=handle, daemon=True)
    t.start()
    return server, port, t


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


class TestConnectorDaemonIntegration(unittest.TestCase):

    def test_ping_pong_over_tcp(self):
        token = secrets.token_hex(16)
        server, port, thread = _start_fake_connector_daemon(token)

        client = ConnectorClient(port=port, token=token)
        client.connect()
        response = client.send_request({"method": "ping"})
        client.close()

        thread.join(timeout=3)
        server.close()

        self.assertEqual(response, {"pong": True})

    def test_daemon_devices_integration(self):
        conn = _start_daemon()
        try:
            response = conn.send_request({"method": "ping"})
            self.assertEqual(response, {"pong": True})
        finally:
            _stop_daemon(conn)

    def test_daemon_real_device_flow(self):
        import tempfile

        conn = _start_daemon()
        try:
            resp = conn.send_request({"method": "ping"})
            self.assertEqual(resp, {"pong": True})

            devices = conn.devices()
            self.assertIsInstance(devices, list)
            serials = [d["serial"] for d in devices]

            expected_device = get_test_device_serial()
            if expected_device == "auto":
                expected_device = serials[0]
            self.assertIn(expected_device, serials)

            ok = conn.select(expected_device, timeout_ms=10000)
            self.assertTrue(ok)

            status = conn.status()
            self.assertIn("serial", status)

            result = conn.shell("echo integration-test-ok")
            self.assertEqual(result["exit"], 0)
            self.assertIn("integration-test-ok", result["stdout"])

            fw = conn.forward(port=9099)
            self.assertTrue(fw.get("ok"))
            un = conn.unforward(port=9099)
            self.assertTrue(un)

            with tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False) as f:
                f.write("push-test-content\n")
                local_push = f.name
            local_pull = f"{local_push}.pulled"
            resp = conn.send_request({
                "method": "push",
                "params": {
                    "local": local_push,
                    "remote": "/sdcard/sts-daemon-push-test.txt",
                },
            })
            self.assertTrue(resp.get("ok"))
            resp = conn.send_request({
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
            conn.shell("rm -f /sdcard/sts-daemon-push-test.txt")
        finally:
            _stop_daemon(conn)


if __name__ == "__main__":
    unittest.main()
