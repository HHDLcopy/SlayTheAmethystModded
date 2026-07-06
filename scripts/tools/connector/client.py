import json
import os
import socket
from pathlib import Path
from typing import Any


class ConnectorClient:

    def __init__(self, socket_path: str | None = None) -> None:
        if socket_path is None:
            socket_path = os.path.join(Path.home(), ".sts", "connector.sock")
        self.socket_path = socket_path
        self._sock: socket.socket | None = None

    def connect(self) -> None:
        self._sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self._sock.connect(self.socket_path)

    def send_request(self, request: dict[str, Any]) -> dict[str, Any]:
        body = json.dumps(request, ensure_ascii=False)
        self._send(body)
        return self._recv_json()

    def devices(self) -> list[dict[str, Any]]:
        resp = self.send_request({"method": "devices"})
        return resp.get("devices", [])

    def select(self, serial: str, timeout_ms: int = 5000) -> bool:
        resp = self.send_request({
            "method": "select",
            "params": {"serial": serial, "timeout_ms": timeout_ms},
        })
        return resp.get("ok", False)

    def status(self) -> dict[str, Any]:
        return self.send_request({"method": "status"})

    def forward(self, port: int) -> dict[str, Any]:
        return self.send_request({
            "method": "forward", "params": {"port": port}})

    def unforward(self, port: int) -> bool:
        resp = self.send_request({
            "method": "unforward", "params": {"port": port}})
        return resp.get("ok", False)

    def shell(self, command: str, timeout_ms: int = 30000) -> dict[str, Any]:
        return self.send_request({
            "method": "shell",
            "params": {"command": command, "timeout_ms": timeout_ms},
        })

    def push(self, local: str, remote: str) -> bool:
        resp = self.send_request({
            "method": "push",
            "params": {"local": local, "remote": remote},
        })
        return resp.get("ok", False)

    def pull(self, remote: str, local: str) -> bool:
        resp = self.send_request({
            "method": "pull",
            "params": {"remote": remote, "local": local},
        })
        return resp.get("ok", False)

    def _send(self, line: str) -> None:
        self._sock.sendall((line + "\n").encode("utf-8"))

    def _recv_json(self) -> dict[str, Any]:
        buffer = b""
        while True:
            chunk = self._sock.recv(4096)
            if not chunk:
                break
            buffer += chunk
            if b"\n" in buffer:
                line, _ = buffer.split(b"\n", 1)
                return json.loads(line.decode("utf-8"))
        if buffer:
            return json.loads(buffer.decode("utf-8"))
        return {}

    def close(self) -> None:
        try:
            self._sock.close()
        except Exception:
            pass
        self._sock = None

    def connect_stream(self, port: int) -> "Stream":
        resp = self.send_request({
            "method": "connect_stream", "params": {"port": port}})
        stream_id = resp.get("stream_id", "unknown")
        sock = self._sock
        self._sock = None
        return Stream(sock=sock, stream_id=stream_id)


class Stream:
    def __init__(self, sock: socket.socket, stream_id: str) -> None:
        self._sock = sock
        self.stream_id = stream_id

    def write(self, data: bytes) -> None:
        self._sock.sendall(data)

    def readline(self) -> bytes:
        buffer = b""
        while True:
            chunk = self._sock.recv(1)
            if not chunk:
                break
            if chunk == b"\n":
                return buffer
            buffer += chunk
        return buffer

    def read(self, size: int = 4096) -> bytes:
        return self._sock.recv(size)

    def close(self) -> None:
        try:
            self._sock.close()
        except Exception:
            pass
