import argparse
import base64
import json
import os
import shutil
import socket as _socket
import subprocess
import sys
import tempfile
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def _resolve_adb_path() -> str:
    adb_name = "adb.exe" if os.name == "nt" else "adb"
    candidates = [
        os.environ.get("ANDROID_SDK_ROOT", ""),
        os.environ.get("ANDROID_HOME", ""),
    ]
    for sdk in [c for c in candidates if c and c.strip()]:
        adb = Path(sdk).expanduser().resolve() / "platform-tools" / adb_name
        if adb.exists():
            return str(adb)
    found = shutil.which("adb")
    if found:
        return found
    return "adb"


def _adb_devices(adb_path: str) -> list[dict[str, str]]:
    try:
        output = subprocess.check_output(
            [adb_path, "devices", "-l"], timeout=10, text=True,
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
    def __init__(self, port: int | None = None, adb_path: str | None = None) -> None:
        self._port = port
        self._running = True
        self._device_serial: str | None = None
        self._server: _socket.socket | None = None
        self._adb_path = adb_path or _resolve_adb_path()
        self._logcat_captures: dict[str, dict[str, Any]] = {}
        self._logcat_lock = threading.Lock()
        self._capture_counter = 0

    def start(self) -> None:
        self._server = _socket.socket(_socket.AF_INET, _socket.SOCK_STREAM)
        self._server.setsockopt(_socket.SOL_SOCKET, _socket.SO_REUSEADDR, 1)
        bind_port = self._port or 0
        self._server.bind(("127.0.0.1", bind_port))
        self._server.listen(5)
        self._server.settimeout(1)

        port = self._server.getsockname()[1]
        print(json.dumps({"port": port, "adb": self._adb_path}))
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

    def _require_device(self) -> dict[str, Any] | None:
        if not self._device_serial:
            return {"error": {"code": -32001, "message": "no device selected"}}
        return None

    def _adb_cmd(self, *args: str) -> list[str]:
        cmd = [self._adb_path]
        if self._device_serial:
            cmd.extend(["-s", self._device_serial])
        cmd.extend(args)
        return cmd

    def _run_adb(
        self,
        args: list[str],
        *,
        timeout_s: float = 30,
        capture: str = "text",
        local_path: str = "",
    ) -> dict[str, Any]:
        err = self._require_device()
        if err is not None and args and args[0] != "devices":
            return err
        cmd = self._adb_cmd(*args)
        try:
            if capture == "none":
                completed = subprocess.run(
                    cmd, timeout=timeout_s, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                return {"exit": completed.returncode, "stdout": "", "stderr": ""}
            if capture == "binary":
                completed = subprocess.run(
                    cmd, timeout=timeout_s, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                if local_path:
                    Path(local_path).parent.mkdir(parents=True, exist_ok=True)
                    Path(local_path).write_bytes(completed.stdout or b"")
                    return {
                        "exit": completed.returncode,
                        "local_path": local_path,
                        "bytes": len(completed.stdout or b""),
                        "stderr": (completed.stderr or b"").decode("utf-8", errors="replace"),
                    }
                return {
                    "exit": completed.returncode,
                    "stdout_b64": base64.b64encode(completed.stdout or b"").decode("ascii"),
                    "stderr": (completed.stderr or b"").decode("utf-8", errors="replace"),
                }
            completed = subprocess.run(
                cmd, timeout=timeout_s, text=True,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            return {
                "exit": completed.returncode,
                "stdout": completed.stdout or "",
                "stderr": "",
            }
        except subprocess.TimeoutExpired as e:
            return {"error": {"code": -32003, "message": f"timeout: {e}"}}
        except Exception as e:
            return {"error": {"code": -32000, "message": str(e)}}

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
                self._adb_cmd("forward", f"tcp:{port}", f"tcp:{port}"),
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
                self._adb_cmd("forward", "--remove", f"tcp:{port}"),
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

    def _logcat_dump(self, params: dict[str, Any]) -> dict[str, Any]:
        err = self._require_device()
        if err is not None:
            return err
        since = str(params.get("since", "") or "").strip()
        local_path = str(params.get("local_path", "") or "").strip()
        timeout_ms = int(params.get("timeout_ms", 15000))
        args = ["logcat", "-d", "-v", "threadtime", "-b", "main", "-b", "system", "-b", "crash"]
        if since:
            args.extend(["-T", since])
        result = self._run_adb(args, timeout_s=timeout_ms / 1000, capture="text")
        if "error" in result:
            return result
        text = result.get("stdout", "")
        if local_path:
            Path(local_path).parent.mkdir(parents=True, exist_ok=True)
            Path(local_path).write_text(text, encoding="utf-8", errors="replace")
            return {"ok": True, "exit": result.get("exit", 0), "local_path": local_path, "bytes": len(text.encode("utf-8", errors="replace"))}
        return {"ok": True, "exit": result.get("exit", 0), "stdout": text, "stderr": ""}

    def _logcat_start(self, params: dict[str, Any]) -> dict[str, Any]:
        err = self._require_device()
        if err is not None:
            return err
        since = str(params.get("since", "") or "").strip()
        local_path = str(params.get("local_path", "") or "").strip()
        if not local_path:
            stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S-%f")
            local_path = str(Path(tempfile.gettempdir()) / f"sts-connector-logcat-{stamp}.txt")
        Path(local_path).parent.mkdir(parents=True, exist_ok=True)
        stderr_path = local_path + ".stderr"
        args = ["logcat", "-v", "threadtime", "-b", "main", "-b", "system", "-b", "crash"]
        args.extend(["-T", since if since else "1"])
        cmd = self._adb_cmd(*args)
        stdout_stream = open(local_path, "wb")
        stderr_stream = open(stderr_path, "wb")
        try:
            process = subprocess.Popen(
                cmd,
                stdout=stdout_stream,
                stderr=stderr_stream,
            )
        except Exception as e:
            stdout_stream.close()
            stderr_stream.close()
            return {"error": {"code": -32000, "message": str(e)}}
        with self._logcat_lock:
            self._capture_counter += 1
            capture_id = f"lc{self._capture_counter}-{uuid.uuid4().hex[:8]}"
            self._logcat_captures[capture_id] = {
                "process": process,
                "stdout_stream": stdout_stream,
                "stderr_stream": stderr_stream,
                "local_path": local_path,
                "stderr_path": stderr_path,
                "started_at": time.time(),
                "command": " ".join(cmd),
            }
        return {
            "ok": True,
            "capture_id": capture_id,
            "local_path": local_path,
            "stderr_path": stderr_path,
        }

    def _logcat_stop(self, params: dict[str, Any]) -> dict[str, Any]:
        capture_id = str(params.get("capture_id", "") or "").strip()
        if not capture_id:
            return {"error": {"code": -32004, "message": "capture_id required"}}
        with self._logcat_lock:
            capture = self._logcat_captures.pop(capture_id, None)
        if capture is None:
            return {"error": {"code": -32000, "message": f"unknown capture_id: {capture_id}"}}
        process: subprocess.Popen = capture["process"]
        stopped_by_daemon = False
        exit_code: int | None = None
        try:
            if process.poll() is None:
                stopped_by_daemon = True
                process.kill()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass
            if process.poll() is not None:
                exit_code = process.returncode
        finally:
            try:
                capture["stdout_stream"].close()
            except Exception:
                pass
            try:
                capture["stderr_stream"].close()
            except Exception:
                pass
        return {
            "ok": True,
            "capture_id": capture_id,
            "local_path": capture["local_path"],
            "stderr_path": capture["stderr_path"],
            "exit": exit_code,
            "stopped_by_daemon": stopped_by_daemon,
            "duration_ms": int((time.time() - capture["started_at"]) * 1000),
            "command": capture["command"],
        }

    def _logcat_status(self, params: dict[str, Any]) -> dict[str, Any]:
        capture_id = str(params.get("capture_id", "") or "").strip()
        with self._logcat_lock:
            if capture_id:
                capture = self._logcat_captures.get(capture_id)
                if capture is None:
                    return {"error": {"code": -32000, "message": f"unknown capture_id: {capture_id}"}}
                running = capture["process"].poll() is None
                return {
                    "ok": True,
                    "capture_id": capture_id,
                    "running": running,
                    "local_path": capture["local_path"],
                }
            items = []
            for cid, capture in self._logcat_captures.items():
                items.append({
                    "capture_id": cid,
                    "running": capture["process"].poll() is None,
                    "local_path": capture["local_path"],
                })
            return {"ok": True, "captures": items}

    def _dispatch(self, req: dict[str, Any], conn: _socket.socket) -> dict[str, Any] | None:
        method = req.get("method", "")
        if method == "ping":
            return {"pong": True}
        elif method == "devices":
            return {"devices": _adb_devices(self._adb_path)}
        elif method == "select":
            params = req.get("params", {})
            serial = params.get("serial", "")
            if serial == "auto":
                devs = [d for d in _adb_devices(self._adb_path) if d.get("state") == "device"]
                if not devs:
                    devs = _adb_devices(self._adb_path)
                if devs:
                    self._device_serial = devs[0]["serial"]
                else:
                    return {"error": {"code": -32001, "message": "no devices"}}
            else:
                self._device_serial = serial
            return {"ok": True, "serial": self._device_serial}
        elif method == "status":
            if not self._device_serial:
                return {"error": {"code": -32001, "message": "no device selected"}}
            devs = _adb_devices(self._adb_path)
            for d in devs:
                if d["serial"] == self._device_serial:
                    return {
                        "serial": d["serial"],
                        "state": "online" if d["state"] == "device" else "offline",
                        "model": d.get("model", ""),
                        "adb": self._adb_path,
                    }
            return {"error": {"code": -32001, "message": "device offline"}}
        elif method == "forward":
            params = req.get("params", {})
            port = params.get("port")
            try:
                subprocess.check_call(
                    self._adb_cmd("forward", f"tcp:{port}", f"tcp:{port}"),
                    timeout=10, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                return {"ok": True, "port": port}
            except Exception as e:
                return {"error": {"code": -32002, "message": str(e)}}
        elif method == "unforward":
            params = req.get("params", {})
            port = params.get("port")
            try:
                subprocess.check_call(
                    self._adb_cmd("forward", "--remove", f"tcp:{port}"),
                    timeout=10, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                return {"ok": True}
            except Exception as e:
                return {"error": {"code": -32002, "message": str(e)}}
        elif method == "shell":
            params = req.get("params", {})
            cmd = params.get("command", "")
            timeout = params.get("timeout_ms", 30000) / 1000
            return self._run_adb(["shell", cmd], timeout_s=timeout, capture="text")
        elif method == "adb":
            params = req.get("params", {})
            args = params.get("args") or []
            if not isinstance(args, list) or not all(isinstance(a, str) for a in args):
                return {"error": {"code": -32004, "message": "params.args must be a list of strings"}}
            timeout = params.get("timeout_ms", 30000) / 1000
            capture = params.get("capture", "text")
            local_path = str(params.get("local_path", "") or "")
            if capture not in {"text", "binary", "none"}:
                return {"error": {"code": -32004, "message": "capture must be text|binary|none"}}
            return self._run_adb(list(args), timeout_s=timeout, capture=capture, local_path=local_path)
        elif method == "install":
            params = req.get("params", {})
            local = params.get("local", "")
            replace = params.get("replace", True)
            timeout = params.get("timeout_ms", 180000) / 1000
            if not local:
                return {"error": {"code": -32004, "message": "local apk path required"}}
            args = ["install"]
            if replace:
                args.append("-r")
            args.append(local)
            return self._run_adb(args, timeout_s=timeout, capture="text")
        elif method == "push":
            params = req.get("params", {})
            local = params.get("local", "")
            remote = params.get("remote", "")
            timeout = params.get("timeout_ms", 30000) / 1000
            try:
                subprocess.check_call(
                    self._adb_cmd("push", local, remote),
                    timeout=timeout, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                return {"ok": True}
            except Exception as e:
                return {"error": {"code": -32000, "message": str(e)}}
        elif method == "pull":
            params = req.get("params", {})
            remote = params.get("remote", "")
            local = params.get("local", "")
            timeout = params.get("timeout_ms", 30000) / 1000
            try:
                subprocess.check_call(
                    self._adb_cmd("pull", remote, local),
                    timeout=timeout, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                return {"ok": True}
            except Exception as e:
                return {"error": {"code": -32000, "message": str(e)}}
        elif method == "logcat_dump":
            return self._logcat_dump(req.get("params", {}))
        elif method == "logcat_start":
            return self._logcat_start(req.get("params", {}))
        elif method == "logcat_stop":
            return self._logcat_stop(req.get("params", {}))
        elif method == "logcat_status":
            return self._logcat_status(req.get("params", {}))
        elif method == "connect_stream":
            return self._connect_stream(req, conn)
        elif method == "quit":
            with self._logcat_lock:
                captures = list(self._logcat_captures.items())
                self._logcat_captures.clear()
            for capture_id, capture in captures:
                try:
                    if capture["process"].poll() is None:
                        capture["process"].kill()
                except Exception:
                    pass
                try:
                    capture["stdout_stream"].close()
                except Exception:
                    pass
                try:
                    capture["stderr_stream"].close()
                except Exception:
                    pass
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
    parser.add_argument("--adb", type=str, default=None,
                        help="Path to adb binary (default: auto-detect)")
    args = parser.parse_args()
    Daemon(port=args.port, adb_path=args.adb).start()


if __name__ == "__main__":
    main()
