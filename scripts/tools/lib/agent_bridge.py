"""TCP client for the game-probe server running inside the game JVM.

Communicates with the AgentBridgeServer over a plain-text line protocol:
    ATTACH <spec> {"key":"value"}
    DETACH <agent_id>
    LIST
    STATUS <agent_id>
    SUBSCRIBE <agent_id>
    UNSUBSCRIBE <agent_id>
    QUIT

Usage:
    bridge = AgentBridge(port=9099)
    bridge.connect()
    agent_id = bridge.attach("tracing@classes=com.megacrit.*")
    bridge.subscribe_and_capture(agent_id, Path("output.jsonl"))
    bridge.detach(agent_id)
    bridge.close()

Delegates to HarnessConnection internally. For cleanup that includes adb forward,
create a HarnessConnection first and pass it in.
"""

from __future__ import annotations

import json
import socket
import time
from pathlib import Path
from typing import Any


class AgentBridgeError(Exception):
    pass


class AgentBridge:
    """High-level agent protocol client.

    Can be used standalone (creates its own socket) or with an existing connection.
    """

    def __init__(
        self,
        host: str = "127.0.0.1",
        port: int = 9099,
        connection: Any = None,
    ) -> None:
        self._host = host
        self._port = port
        self._connection = connection  # HarnessConnection or None
        self._sock: socket.socket | None = None
        self._reader: Any = None
        self._writer: Any = None
        self._owns_connection = connection is None

    def connect(self) -> None:
        if self._connection is not None:
            if not self._connection.is_connected():
                self._connection.connect()
            return
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._sock.settimeout(10)
        self._sock.connect((self._host, self._port))
        self._reader = self._sock.makefile("r", encoding="utf-8", newline="\n")
        self._writer = self._sock.makefile("w", encoding="utf-8", newline="\n")

    def close(self) -> None:
        if self._connection is not None:
            # Don't close the connection — caller owns it
            return
        try:
            self._send("QUIT")
            self._read_line()
        except Exception:
            pass
        try:
            self._sock.close()
        except Exception:
            pass
        self._sock = None
        self._reader = None
        self._writer = None

    def attach(self, spec: str, agent_args: dict[str, Any] | None = None) -> str:
        args_json = json.dumps(agent_args or {})
        response = self._send_recv(f"ATTACH {spec} {args_json}")
        if response.startswith("OK "):
            return response[3:]
        raise AgentBridgeError(response)

    def detach(self, agent_id: str) -> None:
        response = self._send_recv(f"DETACH {agent_id}")
        if response != "OK":
            raise AgentBridgeError(response)

    def list_agents(self) -> list[dict[str, str]]:
        response = self._send_recv("LIST")
        if not response.startswith("MONITORS"):
            raise AgentBridgeError(response)
        agents = []
        parts = response.split()[1:]
        for entry in parts:
            fields = entry.split(":")
            if len(fields) >= 2:
                agents.append({
                    "id": fields[0],
                    "spec": fields[1],
                    "state": fields[2] if len(fields) > 2 else "unknown",
                })
        return agents

    def status(self, agent_id: str) -> dict[str, Any]:
        response = self._send_recv(f"STATUS {agent_id}")
        if not response.startswith("STATUS "):
            raise AgentBridgeError(response)
        parts = response.split()
        return {
            "id": parts[1],
            "state": parts[2],
            "uptime_ms": int(parts[3]),
            "event_count": int(parts[4]),
        }

    def send_command(self, line: str) -> str:
        """Send an arbitrary protocol line and return the response.
        Used for extended commands (OBSERVE, EXEC, etc.) that aren't covered
        by the built-in AgentBridge methods.
        """
        return self._send_recv(line)

    def subscribe_and_capture(
        self,
        agent_id: str,
        output_path: Path,
        timeout_seconds: float | None = None,
    ) -> int:
        if self._connection is not None:
            return self._connection.subscribe_and_capture(
                agent_id, output_path, timeout_seconds
            )
        response = self._send_recv(f"SUBSCRIBE {agent_id}")
        if response != "OK":
            raise AgentBridgeError(response)

        count = 0
        deadline = time.monotonic() + timeout_seconds if timeout_seconds else None
        try:
            with output_path.open("w", encoding="utf-8") as f:
                while True:
                    remain = None
                    if deadline:
                        remain = deadline - time.monotonic()
                        if remain <= 0:
                            break
                        self._sock.settimeout(min(remain, 1.0))
                    else:
                        self._sock.settimeout(1.0)
                    try:
                        line = self._read_line()
                    except socket.timeout:
                        if deadline and time.monotonic() >= deadline:
                            break
                        continue
                    except OSError:
                        break
                    if not line:
                        continue
                    if line.startswith("DATA "):
                        rest = line[5:]
                        first_space = rest.index(" ")
                        json_str = rest[first_space + 1:]
                        f.write(json_str + "\n")
                        f.flush()
                        count += 1
                    elif line.startswith("DATA"):
                        f.write(line[4:].strip() + "\n")
                        f.flush()
                        count += 1
        except Exception:
            pass

        try:
            self._send(f"UNSUBSCRIBE {agent_id}")
            self._read_line()
        except Exception:
            pass

        return count

    def _send_recv(self, line: str) -> str:
        self._send(line)
        return self._read_line()

    def _send(self, line: str) -> None:
        if self._connection is not None:
            self._connection._send(line)
        else:
            self._writer.write(line + "\n")
            self._writer.flush()

    def _read_line(self) -> str:
        if self._connection is not None:
            return self._connection._read_line()
        return self._reader.readline().rstrip("\n\r")
