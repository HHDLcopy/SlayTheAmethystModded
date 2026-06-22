"""Tests for agent_bridge.py protocol parsing."""
from __future__ import annotations

import json
import socket
import sys
import tempfile
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))
from agent_bridge import AgentBridge, AgentBridgeError


class _FakeServer:
    """Minimal test server that accepts one connection and reads commands."""

    def __init__(self):
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._sock.bind(("127.0.0.1", 0))
        self._sock.listen(1)
        self.port = self._sock.getsockname()[1]
        self._thread: threading.Thread | None = None

    def start(self, handler):
        def _run():
            try:
                client, _ = self._sock.accept()
                reader = client.makefile("r", encoding="utf-8", newline="\n")
                writer = client.makefile("w", encoding="utf-8", newline="\n")
                handler(reader, writer)
                client.close()
            except Exception:
                pass

        self._thread = threading.Thread(target=_run, daemon=True)
        self._thread.start()

    def close(self):
        try:
            self._sock.close()
        except Exception:
            pass
        if self._thread:
            self._thread.join(timeout=2)


def _handler_attach_ok(reader, writer):
    for line in reader:
        line = line.strip()
        if line.startswith("ATTACH "):
            writer.write("OK tracing-1\n")
            writer.flush()
        elif line.startswith("DETACH "):
            writer.write("OK\n")
            writer.flush()
        elif line == "LIST":
            writer.write("AGENTS tracing-1:tracing:active\n")
            writer.flush()
        elif line.startswith("STATUS "):
            writer.write("STATUS tracing-1 active 12345 42\n")
            writer.flush()
        elif line == "QUIT":
            writer.write("BYE\n")
            writer.flush()
            break


def _handler_subscribe(reader, writer):
    for line in reader:
        line = line.strip()
        if line.startswith("ATTACH "):
            writer.write("OK tracing-1\n")
            writer.flush()
        elif line == "SUBSCRIBE tracing-1":
            writer.write("OK\n")
            writer.flush()
            for i in range(3):
                writer.write(f'DATA tracing-1 {{"n":{i}}}\n')
                writer.flush()
        elif line == "UNSUBSCRIBE tracing-1":
            writer.write("OK\n")
            writer.flush()
        elif line == "QUIT":
            writer.write("BYE\n")
            writer.flush()
            break


def _handler_error(reader, writer):
    for line in reader:
        line = line.strip()
        if line.startswith("ATTACH "):
            writer.write("ERROR no such monitor\n")
            writer.flush()
        elif line.startswith("DETACH "):
            writer.write("ERROR agent not found\n")
            writer.flush()
        elif line == "QUIT":
            writer.write("BYE\n")
            writer.flush()
            break


class AgentBridgeTest(unittest.TestCase):
    def test_attach_returns_agent_id(self):
        server = _FakeServer()
        server.start(_handler_attach_ok)
        bridge = AgentBridge(port=server.port)
        try:
            bridge.connect()
            agent_id = bridge.attach("tracing")
            self.assertEqual(agent_id, "tracing-1")
        finally:
            bridge.close()
            server.close()

    def test_detach_ok(self):
        server = _FakeServer()
        server.start(_handler_attach_ok)
        bridge = AgentBridge(port=server.port)
        try:
            bridge.connect()
            bridge.detach("tracing-1")
        finally:
            bridge.close()
            server.close()

    def test_list_agents(self):
        server = _FakeServer()
        server.start(_handler_attach_ok)
        bridge = AgentBridge(port=server.port)
        try:
            bridge.connect()
            agents = bridge.list_agents()
            self.assertEqual(len(agents), 1)
            self.assertEqual(agents[0]["id"], "tracing-1")
            self.assertEqual(agents[0]["spec"], "tracing")
            self.assertEqual(agents[0]["state"], "active")
        finally:
            bridge.close()
            server.close()

    def test_status(self):
        server = _FakeServer()
        server.start(_handler_attach_ok)
        bridge = AgentBridge(port=server.port)
        try:
            bridge.connect()
            info = bridge.status("tracing-1")
            self.assertEqual(info["id"], "tracing-1")
            self.assertEqual(info["state"], "active")
            self.assertEqual(info["uptime_ms"], 12345)
            self.assertEqual(info["event_count"], 42)
        finally:
            bridge.close()
            server.close()

    def test_subscribe_and_capture(self):
        server = _FakeServer()
        server.start(_handler_subscribe)
        bridge = AgentBridge(port=server.port)
        try:
            bridge.connect()
            agent_id = bridge.attach("tracing")
            self.assertEqual(agent_id, "tracing-1")

            with tempfile.TemporaryDirectory() as tmpdir:
                output = Path(tmpdir) / "test.jsonl"
                count = bridge.subscribe_and_capture(agent_id, output, timeout_seconds=2.0)
                self.assertEqual(count, 3)
                lines = output.read_text(encoding="utf-8").strip().split("\n")
                self.assertEqual(len(lines), 3)
                self.assertEqual(json.loads(lines[0]), {"n": 0})
                self.assertEqual(json.loads(lines[1]), {"n": 1})
                self.assertEqual(json.loads(lines[2]), {"n": 2})
        finally:
            bridge.close()
            server.close()

    def test_attach_error(self):
        server = _FakeServer()
        server.start(_handler_error)
        bridge = AgentBridge(port=server.port)
        try:
            bridge.connect()
            with self.assertRaisesRegex(AgentBridgeError, "no such monitor"):
                bridge.attach("unknown")
        finally:
            bridge.close()
            server.close()

    def test_detach_error(self):
        server = _FakeServer()
        server.start(_handler_error)
        bridge = AgentBridge(port=server.port)
        try:
            bridge.connect()
            with self.assertRaises(AgentBridgeError):
                bridge.detach("nonexistent")
        finally:
            bridge.close()
            server.close()


if __name__ == "__main__":
    unittest.main()
