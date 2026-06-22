"""High-level typed protocol for agent-connector interaction.

Layers on top of HarnessConnection to provide typed request/response
methods for the extended protocol commands (OBSERVE, EXEC, PERF_START,
PERF_STOP, DUMP_CLASS, REDEFINE_CLASS).

Usage:
    conn = HarnessConnection(adb_runner=harness.adb, port=9099)
    conn.setup_forward()
    conn.connect()
    protocol = AgentProtocol(conn)
    state = protocol.observe()
    result = protocol.execute("PLAY_CARD", {"index": 0})
    b64 = protocol.dump_class("com.example.Foo")
    protocol.redefine_class(b64)
    stats = protocol.perf_stop("tracing-1")
    conn.close()
"""

from __future__ import annotations

import base64
import json
from typing import Any

from .agent_bridge import AgentBridgeError
from .harness_connection import HarnessConnection


class AgentProtocol:
    """Typed protocol client for extended agent-connector commands."""

    def __init__(self, connection: HarnessConnection) -> None:
        if not connection.is_connected():
            raise ValueError("HarnessConnection must be connected before creating AgentProtocol")
        self._conn = connection

    # ── Observe ──────────────────────────────────────────────────────

    def observe(self) -> dict[str, Any]:
        """Get current game state snapshot.

        Returns:
            dict with keys like mode, screen, room, combat, map.
            Until Stage 3, returns stub: {"available": false}.
        """
        resp = self._conn.send_command("OBSERVE")
        if resp.startswith("STATE "):
            return json.loads(resp[6:])
        if resp.startswith("ERROR "):
            raise AgentBridgeError(resp)
        return json.loads(resp) if resp else {}

    # ── Execute ──────────────────────────────────────────────────────

    def execute(self, command: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        """Execute a game command.

        Args:
            command: One of PLAY_CARD, PLAY_CARD_TARGETED, END_TURN,
                     PRESS_PROCEED, SELECT_MAP_NODE, SELECT_BOSS,
                     SKIP_ROOM, CHOOSE_CHARACTER, EMBARK, RETURN_TO_MENU, WAIT.
            params: Command parameters (e.g. {"index": 2, "monsterIndex": 0}).
        """
        args_json = json.dumps(params or {})
        resp = self._conn.send_command(f"EXEC {command} {args_json}")
        if resp.startswith("RESULT "):
            return json.loads(resp[7:])
        if resp.startswith("ERROR "):
            raise AgentBridgeError(resp)
        return {"executed": False, "response": resp}

    # ── Performance ──────────────────────────────────────────────────

    def perf_start(self, agent_id: str) -> None:
        resp = self._conn.send_command(f"PERF_START {agent_id}")
        if resp != "OK":
            raise AgentBridgeError(resp)

    def perf_stop(self, agent_id: str) -> dict[str, Any]:
        resp = self._conn.send_command(f"PERF_STOP {agent_id}")
        if resp.startswith("PERF "):
            return json.loads(resp[5:])
        if resp.startswith("OK"):
            return {"status": "ok"}
        raise AgentBridgeError(resp)

    # ── Class dump / redefine ────────────────────────────────────────

    def dump_class(self, class_name: str) -> bytes:
        """Retrieve the class bytecode for a given fully-qualified class name.

        Returns raw class bytes.
        """
        resp = self._conn.send_command(f"DUMP_CLASS {class_name}")
        if resp.startswith("BYTECODE "):
            b64 = resp[9:]
            return base64.b64decode(b64)
        if resp.startswith("ERROR "):
            raise AgentBridgeError(resp)
        raise AgentBridgeError(f"Unexpected DUMP_CLASS response: {resp}")

    def redefine_class(self, class_bytes: bytes) -> None:
        """Redefine a class at runtime with new bytecode.

        Args:
            class_bytes: The .class file bytes (not base64).
        """
        b64 = base64.b64encode(class_bytes).decode("ascii")
        resp = self._conn.send_command(f"REDEFINE_CLASS {b64}")
        if resp != "OK":
            raise AgentBridgeError(resp)

    # ── Convenience: full hot-reload cycle ───────────────────────────

    def dump_and_save(self, class_name: str, output_path: str) -> bytes:
        """Dump class bytecode and save to a .class file. Returns bytes."""
        data = self.dump_class(class_name)
        with open(output_path, "wb") as f:
            f.write(data)
        return data

    def load_and_redefine(self, class_name: str, class_file_path: str) -> None:
        """Load .class file from disk and redefine in JVM."""
        with open(class_file_path, "rb") as f:
            data = f.read()
        # Validate magic number CAFEBABE
        if data[:4] != b'\xca\xfe\xba\xbe':
            raise ValueError(f"Not a valid class file: {class_file_path}")
        self.redefine_class(data)
