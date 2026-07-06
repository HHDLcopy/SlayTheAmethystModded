import subprocess
from typing import Any


class HarnessOrchestrator:

    def __init__(
        self,
        connector: Any,
        application_id: str,
    ) -> None:
        self._conn = connector
        self._app_id = application_id

    def build_and_install(self) -> None:
        subprocess.check_call(
            ["./gradlew", ":app:assembleDebug"],
            timeout=600)
        subprocess.check_call(
            ["adb", "-s", "localhost:15555", "install", "-r",
             "app/build/outputs/apk/debug/app-debug.apk"],
            timeout=60)

    def start(self, mode: str = "mts", autoplay: bool = False) -> None:
        cmd = (
            f"am start -n {self._app_id}/.MainActivity "
            f"--es launchMode {mode}"
        )
        if autoplay:
            cmd += " --ez io.stamethyst.debug_autoplay true"
        self._conn.shell(cmd)

    def stop(self) -> None:
        self._conn.shell(f"am force-stop {self._app_id}")

    def game_status(self) -> dict[str, Any]:
        result = self._conn.shell(f"pidof {self._app_id}")
        running = result.get("exit", -1) == 0 and bool(result.get("stdout", "").strip())
        return {"running": running}
