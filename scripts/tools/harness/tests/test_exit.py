import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from scripts.tools.harness._context import HarnessContext
from scripts.tools.harness.exit import run_exit


class ExitCommandTest(unittest.TestCase):
    @patch("scripts.tools.harness.exit.time.sleep")
    @patch("scripts.tools.harness.exit.harness_status")
    @patch("scripts.tools.harness.exit.remote_sts_root_script")
    @patch("scripts.tools.harness.exit.resolve_device_sts_root", return_value={"root": "/sts", "accessMode": "shell"})
    def test_exit_uses_gdx_request_and_waits_for_process_exit(self, root, write, status, sleep):
        status.side_effect = [{"processes": {"game": "123"}}, {"processes": {"game": ""}}]
        ctx = HarnessContext(
            options=SimpleNamespace(timeout_seconds=2, poll_interval_seconds=1),
            repo_root=Path("."), application_id="io.stamethyst", result={},
        )
        run_exit(ctx, Path("out"))
        write.assert_called_once()
        self.assertEqual(ctx.result["status"], "EXITED")


if __name__ == "__main__":
    unittest.main()
