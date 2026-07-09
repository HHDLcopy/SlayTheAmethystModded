"""Arthas lifecycle manager.

Loads Arthas into the device JVM via game-probe's LOAD_AGENT
protocol command.  All device I/O goes through connector daemon.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

_ARTHAS_DIR = "/data/data/io.stamethyst/files/arthas"
_RUNTIME_LIB_DIR = (
    "/data/data/io.stamethyst/files/runtimes/Internal/lib/aarch64/server"
)

_RESOURCE_DIR = Path(__file__).resolve().parent / "resource"

_JARS = [
    ("arthas-core.jar", False),
    ("arthas-spy.jar", False),
    ("arthas-bridge.jar", True),
]

_NATIVE_LIBS = [
    ("libprocfs_cpu.so", False),
]

_ASYNC_PROFILER_SO = "libasyncProfiler-linux-arm64.so"


class ArthasManager:
    """Manage Arthas agent lifecycle on the device.

    Usage:
        mgr = ArthasManager(connector=conn, agent_client=agent)
        mgr.start()
        # ... use shell/query or connect_stream(:8099) ...
        mgr.stop()
    """

    def __init__(self, connector: Any, agent_client: Any) -> None:
        self._conn = connector
        self._agent = agent_client

    def start(self, port: int = 8099) -> None:
        # 0. Ensure companion file exists
        self._ensure_companion()

        # 1. Clean up stale .so from old location (migrated to arthas/ dir)
        self._conn.shell(command=f"rm -f /data/data/io.stamethyst/files/libprocfs_cpu.so")

        # 2. Push JARs and native libs to device (idempotent)
        for jar_name, _has_agent_class in _JARS:
            local = str(_RESOURCE_DIR / jar_name)
            remote = f"{_ARTHAS_DIR}/{jar_name}"
            self._conn.push(local=local, remote=remote)

        for lib_name, _ in _NATIVE_LIBS:
            local = str(_RESOURCE_DIR / lib_name)
            remote = f"{_ARTHAS_DIR}/{lib_name}"
            self._conn.push(local=local, remote=remote)

        so_name = _ASYNC_PROFILER_SO
        local_so = str(_RESOURCE_DIR / so_name)
        remote_so = f"{_ARTHAS_DIR}/{so_name}"
        self._conn.push(local=local_so, remote=remote_so)

        # 2b. Push companion debug symbols (AllocTracer symbols for libjvm.so)
        self.push_companion()

        # 3. Load core.jar into system classpath (no Agent-Class),
        #    then load bridge agent via isolated classloader → agentmain
        core_path = f"{_ARTHAS_DIR}/arthas-core.jar"
        agent_path = f"{_ARTHAS_DIR}/arthas-bridge.jar"
        self._agent.send("LOAD_AGENT " + core_path)
        self._agent.load_agent(
            agent_path,
            f"{core_path};port={port}",
        )

        # 4. Forward bridge port
        self._conn.forward(port=port)

    def stop(self, port: int = 8099) -> None:
        self._conn.unforward(port=port)

    # ── Companion file (AllocTracer symbols for stripped libjvm.so) ───

    @staticmethod
    def _ensure_companion() -> None:
        """Download libjvm.debuginfo companion if not present locally."""
        companion_local = (
            _RESOURCE_DIR / "jdk-companion" / "aarch64" / "libjvm.debuginfo"
        )
        if companion_local.is_file():
            return
        from scripts.tools.arthas.download_jvm_companion import download_companion
        download_companion()

    def push_companion(self) -> None:
        """Push libjvm.debuginfo beside libjvm.so for debuglink loading."""
        companion_local = (
            _RESOURCE_DIR / "jdk-companion" / "aarch64" / "libjvm.debuginfo"
        )
        if not companion_local.is_file():
            print("[warn] libjvm.debuginfo not found, alloc profiling won't work")
            return
        remote = f"{_RUNTIME_LIB_DIR}/libjvm.debuginfo"
        self._conn.push(local=str(companion_local), remote=remote)
