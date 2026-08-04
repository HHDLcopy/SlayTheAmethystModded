"""Arthas lifecycle manager.

Loads Arthas into the device JVM via game-probe's LOAD_AGENT
protocol command.  All device I/O goes through connector daemon.
"""

from __future__ import annotations

import importlib.util
import time
from pathlib import Path
from types import ModuleType
from typing import Any

from scripts.tools.arthas.shell import ArthasShell

_ARTHAS_DIR = "/data/data/io.stamethyst/files/arthas"
_RUNTIME_ROOT = "/data/data/io.stamethyst/files/runtimes/Internal"
_RUNTIME_LIB_DIR = f"{_RUNTIME_ROOT}/lib/aarch64/server"
_RUNTIME_JFR_DIR = f"{_RUNTIME_ROOT}/lib/jfr"

_RESOURCE_DIR = Path(__file__).resolve().parent / "resource"
_MODULE_DIR = Path(__file__).resolve().parent

_JARS = [
    ("arthas-core.jar", False),
    ("arthas-spy.jar", False),
    ("arthas-bridge.jar", True),
]

_NATIVE_LIBS = [
    ("libprocfs_cpu.so", False),
]

_ASYNC_PROFILER_SO = "libasyncProfiler-linux-arm64.so"

_JFC_NAMES = ("default.jfc", "profile.jfc")


def _load_hyphen_module(module_filename: str, module_name: str) -> ModuleType:
    """Load a scripts/tools/arthas/*.py file whose name contains hyphens."""
    path = _MODULE_DIR / module_filename
    spec = importlib.util.spec_from_file_location(module_name, path)
    if spec is None or spec.loader is None:
        raise ImportError(f"cannot load {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class ArthasManager:
    """Manage Arthas agent lifecycle on the device.

    Usage:
        mgr = ArthasManager(connector=conn, agent_client=agent)
        mgr.start()
        # ... use shell/query or connect_stream(:8099) ...
        mgr.stop()
    """

    def __init__(self, connector: Any, agent_client: Any, agent_port: int = 9099) -> None:
        self._conn = connector
        self._agent = agent_client
        self._agent_port = agent_port

    def start(self, port: int = 8099) -> None:
        self._conn.arthas_ensure(agent_port=self._agent_port, arthas_port=port)

    def stop(self, port: int = 8099) -> None:
        """Lightweight stop: ask connector daemon to reset Arthas.

        The backend ServerSocket and ArthasBootstrap remain alive so the port
        can be reused without restarting the JVM.  Use shutdown() for a full
        teardown.
        """
        self._conn.arthas_reset(agent_port=self._agent_port, arthas_port=port)

    def shutdown(self, port: int = 8099, wait_timeout: float = 10.0) -> None:
        """Full teardown: ask connector daemon to reset and stop Arthas.

        Arthas ``stop`` destroys the bootstrap; the bridge accept loop notices
        on its next connection and closes its ServerSocket.  This method polls
        until that happens so a subsequent start() can rebind the port.
        """
        self._conn.arthas_shutdown(arthas_port=port)

    def _await_port_release(self, port: int, wait_timeout: float) -> bool:
        """Poll the bridge port until connections stop succeeding.

        Returns True once the port is free, False if wait_timeout elapsed
        while it was still accepting.
        """
        deadline = time.monotonic() + wait_timeout
        while time.monotonic() < deadline:
            probe = None
            try:
                self._conn.forward(port=port)
                probe = self._conn.connect_stream(port=port)
            except Exception:
                # Cannot reach the port any more: the listener is gone.
                return True
            finally:
                if probe is not None:
                    try:
                        probe.close()
                    except Exception:
                        pass
                try:
                    self._conn.unforward(port=port)
                except Exception:
                    pass
            time.sleep(0.3)
        return False

    # ── Companion file (AllocTracer symbols for stripped libjvm.so) ───

    @staticmethod
    def _ensure_companion() -> None:
        """Download libjvm.debuginfo companion if not present locally."""
        companion_local = (
            _RESOURCE_DIR / "jdk-companion" / "aarch64" / "libjvm.debuginfo"
        )
        if companion_local.is_file():
            return
        mod = _load_hyphen_module(
            "download-jvm-companion.py",
            "scripts.tools.arthas.download_jvm_companion",
        )
        mod.download_companion()

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

    # ── JFR config templates (missing from runtime-pack) ─────────────

    @staticmethod
    def _ensure_jfr_jfc() -> None:
        """Download default.jfc / profile.jfc if not present locally."""
        local_dir = _RESOURCE_DIR / "jdk-companion" / "jfr"
        if all((local_dir / name).is_file() for name in _JFC_NAMES):
            return
        mod = _load_hyphen_module(
            "download-jfr-jfc.py",
            "scripts.tools.arthas.download_jfr_jfc",
        )
        mod.download_jfr_jfc()

    def push_jfr_jfc(self) -> None:
        """Push JFR .jfc templates to java.home/lib/jfr on the device."""
        local_dir = _RESOURCE_DIR / "jdk-companion" / "jfr"
        missing = [n for n in _JFC_NAMES if not (local_dir / n).is_file()]
        if missing:
            print(f"[warn] JFR .jfc missing locally ({missing}); jfr command may fail")
            return
        # ServerSocket path is java.home/lib/jfr; create dir then push files.
        self._conn.shell(command=f"mkdir -p {_RUNTIME_JFR_DIR}")
        for name in _JFC_NAMES:
            local = str(local_dir / name)
            remote = f"{_RUNTIME_JFR_DIR}/{name}"
            self._conn.push(local=local, remote=remote)
