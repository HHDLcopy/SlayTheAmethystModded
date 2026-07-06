from __future__ import annotations

import subprocess
from typing import Any


def deploy_game_probe(connector: Any, app_id: str) -> None:
    subprocess.check_call(
        ["./gradlew", ":game-probe:fatJar"], timeout=120)
    connector.push(
        local="game-probe/build/libs/game-probe.jar",
        remote=f"/data/data/{app_id}/files/game_probe/game-probe.jar",
    )
