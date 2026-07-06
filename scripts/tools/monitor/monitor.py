from __future__ import annotations

from typing import Any


class DeviceMonitor:

    def __init__(self, connector_client: Any) -> None:
        self._conn = connector_client

    def screenshot(self, output_path: str) -> None:
        remote_tmp = "/sdcard/sts-monitor-tmp.png"
        self._conn.shell(f"screencap -p {remote_tmp}")
        self._conn.pull(remote=remote_tmp, local=output_path)
        self._conn.shell(f"rm -f {remote_tmp}")

    def pull(self, remote: str, local: str) -> None:
        self._conn.pull(remote=remote, local=local)
