import argparse
import json
import socket as _socket
import subprocess
import sys
import threading
from typing import Any


def _adb_devices() -> list[dict[str, str]]:
    try:
        output = subprocess.check_output(
            ["adb", "devices", "-l"], timeout=10, text=True,
            stderr=subprocess.STDOUT)
    except Exception:
        return []
    devices: list[dict[str, str]] = []
    for line in output.strip().split("\n")[1:]:
        if not line.strip():
            continue
        parts = line.strip().split()
        if len(parts) < 2:
            continue
        serial, state = parts[0], parts[1]
        model = ""
        for part in parts[2:]:
            if part.startswith("model:"):
                model = part.split(":", 1)[1]
        devices.append({"serial": serial, "state": state, "model": model})
    return devices


class Daemon:
    def __init__(self, port: int | None = None) -> None:
        self._port = port
        self._running = True
        self._device_serial: str | None = None
        self._server: _socket.socket | None = None

    def start(self) -> None:
        self._server = _socket.socket(_socket.AF_INET, _socket.SOCK_STREAM)
        self._server.setsockopt(_socket.SOL_SOCKET, _socket.SO_REUSEADDR, 1)
        bind_port = self._port or 0
        self._server.bind(("127.0.0.1", bind_port))
        self._server.listen(5)
        self._server.settimeout(1)

        port = self._server.getsockname()[1]
        print(json.dumps({"port": port}))
        sys.stdout.flush()

        while self._running:
            try:
                conn, _ = self._server.accept()
            except _socket.timeout:
                continue
            except OSError:
                break
            t = threading.Thread(target=self._handle, args=(conn,), daemon=True)
            t.start()

    def _handle(self, conn: _socket.socket) -> None:
        try:
            conn.settimeout(30)
            reader = conn.makefile("r", encoding="utf-8", newline="\n")
            while self._running:
                line = reader.readline()
                if not line:
                    break
                line = line.strip()
                if not line:
                    continue
                try:
                    req = json.loads(line)
                except json.JSONDecodeError:
                    self._respond(conn, {"error": {"code": -32000, "message": "invalid json"}})
                    continue
                resp = self._dispatch(req, conn)
                if resp is None:
                    return
                self._respond(conn, resp)
        except Exception:
            pass
        finally:
            try:
                conn.close()
            except Exception:
                pass

    def _connect_stream(self, req: dict[str, Any], client_conn: _socket.socket) -> None:
        params = req.get("params", {})
        port = params.get("port", 0)
        stream_id = f"s{port}"

        if self._device_serial:
            subprocess.run(
                ["adb", "-s", self._device_serial, "forward",
                 f"tcp:{port}", f"tcp:{port}"],
                timeout=10, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        dev_conn = _socket.socket(_socket.AF_INET, _socket.SOCK_STREAM)
        dev_conn.settimeout(10)
        try:
            dev_conn.connect(("127.0.0.1", port))
        except Exception as e:
            self._respond(client_conn, {"error": {"code": -32000, "message": f"connect failed: {e}"}})
            return

        self._respond(client_conn, {"stream_id": stream_id})
        self._do_passthrough(client_conn, dev_conn)
        dev_conn.close()
        if self._device_serial:
            subprocess.run(
                ["adb", "-s", self._device_serial, "forward",
                 "--remove", f"tcp:{port}"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                timeout=5)

    @staticmethod
    def _do_passthrough(client: _socket.socket, device: _socket.socket) -> None:
        import select
        client.setblocking(False)
        device.setblocking(False)
        try:
            while True:
                readable, _, _ = select.select([client, device], [], [], 1.0)
                if client in readable:
                    data = client.recv(8192)
                    if not data:
                        break
                    device.sendall(data)
                if device in readable:
                    data = device.recv(8192)
                    if not data:
                        break
                    client.sendall(data)
        except Exception:
            pass

    def _dispatch(self, req: dict[str, Any], conn: _socket.socket) -> dict[str, Any] | None:
        method = req.get("method", "")
        if method == "ping":
            return {"pong": True}
        elif method == "devices":
            return {"devices": _adb_devices()}
        elif method == "select":
            params = req.get("params", {})
            serial = params.get("serial", "")
            if serial == "auto":
                devs = _adb_devices()
                if devs:
                    self._device_serial = devs[0]["serial"]
                else:
                    return {"error": {"code": -32001, "message": "no devices"}}
            else:
                self._device_serial = serial
            return {"ok": True}
        elif method == "status":
            if not self._device_serial:
                return {"error": {"code": -32001, "message": "no device selected"}}
            devs = _adb_devices()
            for d in devs:
                if d["serial"] == self._device_serial:
                    return {
                        "serial": d["serial"],
                        "state": "online" if d["state"] == "device" else "offline",
                        "model": d.get("model", ""),
                    }
            return {"error": {"code": -32001, "message": "device offline"}}
        elif method == "forward":
            params = req.get("params", {})
            port = params.get("port")
            try:
                subprocess.check_call(
                    ["adb", "-s", self._device_serial, "forward",
                     f"tcp:{port}", f"tcp:{port}"],
                    timeout=10, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                return {"ok": True, "port": port}
            except Exception as e:
                return {"error": {"code": -32002, "message": str(e)}}
        elif method == "unforward":
            params = req.get("params", {})
            port = params.get("port")
            try:
                subprocess.check_call(
                    ["adb", "-s", self._device_serial, "forward",
                     "--remove", f"tcp:{port}"],
                    timeout=10, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                return {"ok": True}
            except Exception as e:
                return {"error": {"code": -32002, "message": str(e)}}
        elif method == "shell":
            params = req.get("params", {})
            cmd = params.get("command", "")
            timeout = params.get("timeout_ms", 30000) / 1000
            try:
                output = subprocess.check_output(
                    ["adb", "-s", self._device_serial, "shell", cmd],
                    timeout=timeout, text=True,
                    stderr=subprocess.STDOUT)
                return {"exit": 0, "stdout": output, "stderr": ""}
            except subprocess.CalledProcessError as e:
                return {"exit": e.returncode, "stdout": e.output or "", "stderr": ""}
            except Exception as e:
                return {"error": {"code": -32000, "message": str(e)}}
        elif method == "push":
            params = req.get("params", {})
            local = params.get("local", "")
            remote = params.get("remote", "")
            try:
                subprocess.check_call(
                    ["adb", "-s", self._device_serial, "push", local, remote],
                    timeout=30, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                return {"ok": True}
            except Exception as e:
                return {"error": {"code": -32000, "message": str(e)}}
        elif method == "pull":
            params = req.get("params", {})
            remote = params.get("remote", "")
            local = params.get("local", "")
            try:
                subprocess.check_call(
                    ["adb", "-s", self._device_serial, "pull", remote, local],
                    timeout=30, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                return {"ok": True}
            except Exception as e:
                return {"error": {"code": -32000, "message": str(e)}}
        elif method == "connect_stream":
            return self._connect_stream(req, conn)
        elif method == "quit":
            self._running = False
            return {"ok": True}
        else:
            return {"error": {"code": -32000, "message": f"unknown method: {method}"}}

    def _respond(self, conn: _socket.socket, resp: dict[str, Any]) -> None:
        try:
            body = json.dumps(resp, ensure_ascii=False) + "\n"
            conn.sendall(body.encode("utf-8"))
        except Exception:
            pass

    def stop(self) -> None:
        self._running = False


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=None,
                        help="TCP port (random by default)")
    args = parser.parse_args()
    Daemon(port=args.port).start()


if __name__ == "__main__":
    main()
