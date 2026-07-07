import subprocess
from typing import Any

from scripts.tools.lib.env_device import get_test_device_serial


class HarnessOrchestrator:

    def __init__(
        self,
        connector: Any,
        application_id: str,
        device_serial: str | None = None,
    ) -> None:
        self._conn = connector
        self._app_id = application_id
        self._device_serial = device_serial or get_test_device_serial()

    def build_and_install(self) -> None:
        subprocess.check_call(
            ["./gradlew", ":app:assembleDebug"],
            timeout=600)
        subprocess.check_call(
            ["adb", "-s", self._device_serial, "install", "-r",
             "app/build/outputs/apk/debug/app-debug.apk"],
            timeout=60)

    def start(self, mode: str = "mts", debug_mode: bool = False, autoplay: bool = False) -> None:
        cmd = (
            f"am start -n {self._app_id}/.MainActivity "
            f"--es launchMode {mode}"
        )
        if debug_mode:
            cmd += " --ez io.stamethyst.debug_mode true"
        if autoplay:
            cmd += " --ez io.stamethyst.debug_autoplay true"
        self._conn.shell(cmd)

    def stop(self) -> None:
        self._conn.shell(f"am force-stop {self._app_id}")

    def game_status(self) -> dict[str, Any]:
        result = self._conn.shell(f"pidof {self._app_id}")
        running = result.get("exit", -1) == 0 and bool(result.get("stdout", "").strip())
        return {"running": running}
