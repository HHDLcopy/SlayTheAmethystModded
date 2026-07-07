"""Arthas lifecycle manager.

Loads Arthas into the device JVM via game-probe's LOAD_AGENT
protocol command.  All device I/O goes through connector daemon.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

_ARTHAS_DIR = "/data/data/io.stamethyst/files/arthas"

_RESOURCE_DIR = Path(__file__).resolve().parent / "resource"

_JARS = [
    ("arthas-core.jar", False),
    ("arthas-bridge.jar", True),
]


class ArthasManager:
    """Manage Arthas agent lifecycle on the device.

    Usage:
        mgr = ArthasManager(connector=conn, agent_client=agent)
        mgr.start()
        # ... use HTTP :8563 or telnet :3658 ...
        mgr.stop()
    """

    def __init__(self, connector: Any, agent_client: Any) -> None:
        self._conn = connector
        self._agent = agent_client

    def start(
        self,
        http_port: int = 8563,
        telnet_port: int = 3658,
    ) -> None:
        # 1. Push JARs to device (idempotent)
        for jar_name, has_agent_class in _JARS:
            local = str(_RESOURCE_DIR / jar_name)
            remote = f"{_ARTHAS_DIR}/{jar_name}"
            self._conn.push(local=local, remote=remote)

        # 2. Load core.jar into system classpath (no Agent-Class),
        #    then load bridge agent via isolated classloader → agentmain
        core_path = f"{_ARTHAS_DIR}/arthas-core.jar"
        agent_path = f"{_ARTHAS_DIR}/arthas-bridge.jar"
        self._agent.send("LOAD_AGENT " + core_path)
        self._agent.load_agent(
            agent_path,
            f"{core_path};port={telnet_port}",
        )

        # 3. Forward bridge port
        self._conn.forward(port=telnet_port)

    def stop(self, telnet_port: int = 3658) -> None:
        self._conn.unforward(port=telnet_port)
