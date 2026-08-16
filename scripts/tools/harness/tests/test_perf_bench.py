import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from scripts.tools.harness._context import HarnessContext
from scripts.tools.harness.perf_bench import run_perf_bench


class PerfBenchTest(unittest.TestCase):
    @patch("scripts.tools.harness.perf_bench._stop_game")
    @patch("scripts.tools.harness.perf_bench.start_logcat_capture")
    @patch("scripts.tools.harness.perf_bench.device_logcat_timestamp", return_value="")
    @patch("scripts.tools.harness.perf_bench.clear_runtime_signals")
    @patch("scripts.tools.harness.perf_bench.run_start")
    @patch("scripts.tools.harness.perf_bench.harness_status")
    def test_startup_failure_after_game_pid_is_not_reported_as_no_incidents(
        self, status, run_start, clear, timestamp, start_logcat, stop_game
    ):
        status.return_value = {
            "observedState": "FAIL",
            "processes": {"game": "123"},
        }
        ctx = HarnessContext(
            options=SimpleNamespace(
                skip_install=True,
                timeout_seconds=1,
                poll_interval_seconds=1,
                autoplay=False,
                autoplay_mode="normal",
                autoplay_save_mode="fresh",
                single_room_character="",
                perf_bench_character="",
            ),
            repo_root=Path("."),
            application_id="io.stamethyst",
            resolved_device_serial="device",
            result={},
        )

        self.assertEqual(run_perf_bench(ctx, Path("out")), 0)

        run_start.assert_called_once()
        self.assertFalse(ctx.result["success"])
        self.assertEqual(ctx.result["status"], "FAIL")


if __name__ == "__main__":
    unittest.main()
