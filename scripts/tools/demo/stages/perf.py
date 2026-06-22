"""Stage 3 — Performance overhead test.

Attaches two tracing monitors sequentially:
  1. Without `@locals`       → baseline
  2. With    `@locals=true`   → instrumented

Measures each for PERF_DURATION seconds, then prints a comparison table.
"""

from __future__ import annotations

import json
import math
import time
from typing import TYPE_CHECKING

from .base import Stage

if TYPE_CHECKING:
    from ..demo_runner import DemoRunner


PERF_DURATION = 8
TRACE_TARGET = (
    "tracing@classes=io.stamethyst.compatmod.autoplay.AutoplayDriver"
    "@methods=onCardCrawlGameUpdate"
)


def _safe_int(val: object) -> int:
    try:
        return int(float(str(val)))
    except (ValueError, TypeError):
        return 0


def _safe_float(val: object) -> float:
    try:
        v = float(str(val))
        return v if math.isfinite(v) else 0.0
    except (ValueError, TypeError):
        return 0.0


class PerfStage(Stage):
    id = "perf"
    name = "Performance Overhead Test"

    def run(self, runner: DemoRunner, out_dir: str) -> dict:
        proto = runner._proto
        bridge = runner._bridge

        # ── Baseline (no locals) ─────────────────────────────────
        aid1 = bridge.attach(TRACE_TARGET); runner._log_op("ATTACH baseline", f"OK {aid1}")
        proto.perf_start(aid1)
        time.sleep(PERF_DURATION)
        baseline = proto.perf_stop(aid1); runner._log_op(f"PERF_STOP {aid1}", json.dumps(baseline))
        runner.conn.send_command(f"DETACH {aid1}"); runner._log_op(f"DETACH {aid1}", "OK")

        # ── With locals ──────────────────────────────────────────
        aid2 = bridge.attach(TRACE_TARGET + "@locals=true"); runner._log_op("ATTACH locals", f"OK {aid2}")
        proto.perf_start(aid2)
        time.sleep(PERF_DURATION)
        locals_result = proto.perf_stop(aid2); runner._log_op(f"PERF_STOP {aid2}", json.dumps(locals_result))
        runner.conn.send_command(f"DETACH {aid2}"); runner._log_op(f"DETACH {aid2}", "OK")

        # ── Comparison ───────────────────────────────────────────
        b_events = _safe_int(baseline.get("events"))
        l_events = _safe_int(locals_result.get("events"))
        b_avg = _safe_float(baseline.get("avg_ns"))
        l_avg = _safe_float(locals_result.get("avg_ns"))
        b_rate = _safe_float(baseline.get("rate_per_sec"))
        l_rate = _safe_float(locals_result.get("rate_per_sec"))

        overhead_ns = l_avg - b_avg if b_avg > 0 else 0.0
        overhead_pct = (l_avg / b_avg - 1.0) * 100 if b_avg > 0 else 0.0

        comparison = {
            "baseline": {"avg_ns": int(b_avg), "events": b_events, "rate_per_sec": round(b_rate, 1)},
            "with_locals": {"avg_ns": int(l_avg), "events": l_events, "rate_per_sec": round(l_rate, 1)},
            "overhead_ns": int(overhead_ns),
            "overhead_pct": round(overhead_pct, 1),
        }
        with open(f"{out_dir}/comparison.json", "w", encoding="utf-8") as f:
            json.dump(comparison, f, indent=2)

        ok = b_events > 0
        return {
            "success": ok,
            "status": f"+{overhead_pct:.1f}%" if ok and overhead_pct > 0 else "N/A" if b_events == 0 else f"-{abs(overhead_pct):.1f}%",
            "message": (
                f"Baseline: {b_avg:.0f}ns avg ({b_events} evts) | "
                f"With locals: {l_avg:.0f}ns avg ({l_events} evts) | "
                f"Overhead: {overhead_ns:+.0f}ns ({overhead_pct:+.1f}%)"
            ),
            "data": comparison,
        }
