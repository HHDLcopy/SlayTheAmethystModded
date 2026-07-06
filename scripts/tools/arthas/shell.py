from __future__ import annotations

from scripts.tools.connector.client import Stream


class ArthasShell:

    def __init__(self, stream: Stream) -> None:
        self._stream = stream
        self._sock = stream._sock

    def command(self, cmd: str, timeout: float = 15) -> str:
        self._drain_prompt()
        self._stream.write((cmd + "\n").encode("utf-8"))
        return self._read_output(timeout)

    def _drain_prompt(self) -> None:
        self._sock.settimeout(1)
        try:
            self._sock.recv(8192)
        except Exception:
            pass

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
        text = buf.decode("utf-8", errors="replace").strip()
        end = text.rfind("\n[arthas@")
        if end >= 0:
            text = text[:end].strip()
        return text
