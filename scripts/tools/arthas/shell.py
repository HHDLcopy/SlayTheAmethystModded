from __future__ import annotations

from typing import Callable

from scripts.tools.connector.client import Stream

_TYPE_NOT_PRESENT = "TypeNotPresentException"


class ArthasShell:

    def __init__(
        self,
        stream: Stream,
        reconnect_fn: Callable[[], Stream] | None = None,
    ) -> None:
        self._stream = stream
        self._sock = stream._sock
        self._reconnect_fn = reconnect_fn
        self._retried = False

    def command(self, cmd: str, timeout: float = 15) -> str:
        self._drain_prompt()
        self._stream.write((cmd + "\n").encode("utf-8"))
        result = self._read_output(timeout)
        if (
            not self._retried
            and self._reconnect_fn is not None
            and _TYPE_NOT_PRESENT in result
        ):
            self._retried = True
            self._stream.close()
            self._stream = self._reconnect_fn()
            self._sock = self._stream._sock
            return self.command(cmd, timeout)
        return result

    def _drain_prompt(self) -> None:
        self._sock.settimeout(0.5)
        while True:
            try:
                data = self._sock.recv(8192)
                if not data:
                    break
            except Exception:
                break

    def _read_output(self, timeout: float) -> str:
        self._sock.settimeout(timeout)
        buf = b""
        while True:
            try:
                chunk = self._sock.recv(8192)
            except Exception:
                break
            if not chunk:
                break
            buf += chunk
            if b"$ " in buf:
                break
        if not buf:
            raise RuntimeError("Arthas shell returned no output before timeout or close")
        text = buf.decode("utf-8", errors="replace").strip()
        end = text.rfind("\n[arthas@")
        if end >= 0:
            text = text[:end].strip()
        return text
