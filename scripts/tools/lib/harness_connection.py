"""General TCP connection manager for the game-probe running inside the JVM.

Wraps adb port forwarding and TCP socket management into a single reusable context manager.
Replaces the scattered adb(["forward", ...]) + AgentBridge(...) + bridge.close() pattern
found in Harness agent methods.

Usage:
    conn = HarnessConnection(adb_runner=harness.adb, port=9099)
    conn.setup_forward()
    conn.connect()
    response = conn.send_command("LIST")
    conn.subscribe_events(callback=handle_event, timeout_seconds=30)
    conn.close()  # or use as context manager
"""

from __future__ import annotations

import socket
import time
from pathlib import Path
from types import TracebackType
from typing import Any, Callable

from .agent_bridge import AgentBridgeError


class HarnessConnection:
    """Manages adb forward + TCP connection to the JVM game-probe server."""

    def __init__(
        self,
        adb_runner: Callable[..., Any],
        host: str = "127.0.0.1",
        port: int = 9099,
    ) -> None:
        self._adb = adb_runner
        self._host = host
        self._port = port
        self._sock: socket.socket | None = None
        self._reader: Any = None
        self._writer: Any = None
        self._forwarded = False

    # ── adb forward management ──────────────────────────────────────────

    def setup_forward(self) -> None:
        """Set up adb port forward host:tcp:port -> device:tcp:port."""
        self._adb(["forward", f"tcp:{self._port}", f"tcp:{self._port}"])
        self._forwarded = True

    def remove_forward(self) -> None:
        """Remove the adb port forward. Safe to call multiple times."""
        if self._forwarded:
            try:
                self._adb(["forward", "--remove", f"tcp:{self._port}"])
            except Exception:
                pass
            self._forwarded = False

    # ── TCP lifecycle ───────────────────────────────────────────────────

    def connect(self, timeout: float = 10.0) -> None:
        """Open TCP connection to the forwarded game-probe server."""
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._sock.settimeout(timeout)
        self._sock.connect((self._host, self._port))
        self._reader = self._sock.makefile("r", encoding="utf-8", newline="\n")
        self._writer = self._sock.makefile("w", encoding="utf-8", newline="\n")

    def close(self) -> None:
        """Gracefully send QUIT and close the TCP connection."""
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

    def is_connected(self) -> bool:
        return self._sock is not None

    # ── Protocol I/O ────────────────────────────────────────────────────

    def send_command(self, line: str) -> str:
        """Send a protocol line and return the single response line."""
        self._send(line)
        return self._read_line()

    def subscribe_events(
        self,
        callback: Callable[[str], None],
        timeout_seconds: float | None = None,
        *,
        poll_interval: float = 0.2,
    ) -> int:
        """Enter an event loop reading DATA lines and calling `callback(json_payload)`.

        Returns the total event count.
        Does NOT send SUBSCRIBE/UNSUBSCRIBE -- the caller must manage subscription.
        """
        count = 0
        deadline = time.monotonic() + timeout_seconds if timeout_seconds else None
        while True:
            remain = None
            if deadline:
                remain = deadline - time.monotonic()
                if remain <= 0:
                    break
                self._sock.settimeout(min(remain, poll_interval))
            else:
                self._sock.settimeout(poll_interval)
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
                callback(json_str)
                count += 1
            elif line.startswith("DATA"):
                callback(line[4:].strip())
                count += 1
        return count

    def subscribe_and_capture(
        self,
        agent_id: str,
        output_path: Path,
        timeout_seconds: float | None = None,
    ) -> int:
        """Send SUBSCRIBE, capture DATA events to file, send UNSUBSCRIBE.
        Convenience wrapper combining subscribe/capture/unsubscribe.
        """
        resp = self.send_command(f"SUBSCRIBE {agent_id}")
        if resp != "OK":
            raise AgentBridgeError(resp)

        count = 0
        try:
            with output_path.open("w", encoding="utf-8") as f:
                def write_line(json_str: str) -> None:
                    nonlocal count
                    f.write(json_str + "\n")
                    f.flush()
                    # count updated in closure
                deadline = time.monotonic() + timeout_seconds if timeout_seconds else None
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
        finally:
            try:
                self._send(f"UNSUBSCRIBE {agent_id}")
                self._read_line()
            except Exception:
                pass
        return count

    def subscribe_events_with_unsubscribe(
        self,
        agent_id: str,
        callback: Callable[[str], None],
        timeout_seconds: float | None = None,
        *,
        poll_interval: float = 0.2,
    ) -> int:
        """Send SUBSCRIBE, call callback for each DATA event, send UNSUBSCRIBE.
        Returns total event count.
        """
        resp = self.send_command(f"SUBSCRIBE {agent_id}")
        if resp != "OK":
            raise AgentBridgeError(resp)
        try:
            return self.subscribe_events(callback, timeout_seconds, poll_interval=poll_interval)
        finally:
            try:
                self._send(f"UNSUBSCRIBE {agent_id}")
                self._read_line()
            except Exception:
                pass

    # ── Context manager ─────────────────────────────────────────────────

    def __enter__(self) -> "HarnessConnection":
        self.setup_forward()
        self.connect()
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc_val: BaseException | None,
        exc_tb: TracebackType | None,
    ) -> None:
        self.close()
        self.remove_forward()

    # ── Internal ────────────────────────────────────────────────────────

    def _send(self, line: str) -> None:
        self._writer.write(line + "\n")
        self._writer.flush()

    def _read_line(self) -> str:
        return self._reader.readline().rstrip("\n\r")
