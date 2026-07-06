from __future__ import annotations

import subprocess
from typing import Any


def deploy_agent_connector(connector: Any, app_id: str) -> None:
    subprocess.check_call(
        ["./gradlew", ":agent-connector:fatJar"], timeout=120)
    connector.push(
        local="agent-connector/build/libs/agent-connector.jar",
        remote=f"/data/data/{app_id}/files/agent_connector/agent-connector.jar",
    )
