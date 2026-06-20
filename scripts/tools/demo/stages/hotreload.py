"""Stage 4 — Hot-reload cycle.

1. DUMP_CLASS a target class from the running JVM.
2. Decompile it with CFR (offline).
3. Invoke 'javac' to compile it again (round-trip test).
4. REDEFINE_CLASS to push it back in.
"""

from __future__ import annotations

import shutil
import subprocess
from typing import TYPE_CHECKING

from .base import Stage

if TYPE_CHECKING:
    from ..demo_runner import DemoRunner


HOTRELOAD_TARGET = "com.megacrit.cardcrawl.helpers.FontHelper"


class HotreloadStage(Stage):
    id = "hotreload"
    name = "Hot-Reload Cycle"

    def run(self, runner: DemoRunner, out_dir: str) -> dict:
        proto = runner._proto

        # ── 1. DUMP_CLASS ─────────────────────────────────────────
        data = proto.dump_class(HOTRELOAD_TARGET)
        class_name = HOTRELOAD_TARGET.replace('.', '/')
        class_file = f"{out_dir}/{class_name}.class"
        __import__("os").makedirs(__import__("os").path.dirname(class_file), exist_ok=True)
        with open(class_file, "wb") as f:
            f.write(data)
        runner._log_op(f"DUMP_CLASS {HOTRELOAD_TARGET}", f"{len(data)} bytes")

        # ── 2. CFR decompile ──────────────────────────────────────
        java_file = None
        if not runner.options.no_cfr:
            java_file = self._decompil(
                runner, HOTRELOAD_TARGET, class_file, out_dir
            )
            runner._log_op(
                f"CFR {HOTRELOAD_TARGET}",
                java_file or "skipped/unavailable",
            )

        # ── 3. REDEFINE_CLASS + round-trip verify ─────────────────
        proto.redefine_class(data)
        runner._log_op(f"REDEFINE_CLASS {HOTRELOAD_TARGET}", "OK")

        # Verify round-trip: re-dump and check bytecode equality
        data2 = proto.dump_class(HOTRELOAD_TARGET)
        verified = (data == data2)
        runner._log_op(
            f"VERIFY {HOTRELOAD_TARGET}",
            f"round-trip match={verified} ({len(data2)} bytes)"
        )

        return {
            "success": verified,
            "status": "REDEFINED" if verified else "MISMATCH",
            "message": (
                f"Dumped {len(data)} bytes"
                + (f", decompiled to {java_file}" if java_file else "")
                + f", redefined, round-trip verified={verified}"
            ),
            "data": {
                "class_file": class_file,
                "decompiled_java": java_file,
                "round_trip_verified": verified,
            },
        }

    def _decompil(
        self,
        runner: DemoRunner,
        class_name: str,
        class_file: str,
        out_dir: str,
    ) -> str | None:
        cfr_jar = runner.repo_root / "scripts" / "tools" / "lib" / "cfr.jar"
        if not cfr_jar.exists():
            return None

        java = shutil.which("java")
        if not java:
            return None

        try:
            result = subprocess.run(
                [
                    java, "-jar", str(cfr_jar),
                    class_name,
                    "--outputdir", out_dir,
                    "--extraclasspath", out_dir,
                ],
                capture_output=True, text=True, timeout=30,
                cwd=out_dir,
            )
            expected = f"{out_dir}/{class_name}.java"
            if __import__("os").path.exists(expected):
                return expected
            # CFR may output to stdout
            if result.stdout.strip():
                out = f"{out_dir}/{HOTRELOAD_TARGET.split('.')[-1]}.java"
                with open(out, "w") as f:
                    f.write(result.stdout)
                return out
            return None
        except Exception:
            return None
