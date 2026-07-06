from __future__ import annotations

import unittest
from unittest.mock import MagicMock, patch

from scripts.tools.connector.client import ConnectorClient
from scripts.tools.lib.agent_client import AgentClient


class TestHarnessOrchestrator(unittest.TestCase):

    def setUp(self):
        self.mock_conn = MagicMock(spec=ConnectorClient)
        self.mock_agent = MagicMock(spec=AgentClient)

    def test_stop_sends_force_stop(self):
        from scripts.tools.harness.orchestrator import HarnessOrchestrator
        orch = HarnessOrchestrator(
            connector=self.mock_conn,
            application_id="io.stamethyst.debug",
        )
        orch.stop()
        self.mock_conn.shell.assert_called_once_with(
            "am force-stop io.stamethyst.debug")

    def test_start_sends_am_start_mts(self):
        from scripts.tools.harness.orchestrator import HarnessOrchestrator
        self.mock_conn.shell.return_value = {
            "exit": 0, "stdout": "", "stderr": ""}
        orch = HarnessOrchestrator(
            connector=self.mock_conn,
            application_id="io.stamethyst.debug",
        )
        orch.start(mode="mts", autoplay=True)
        cmd = self.mock_conn.shell.call_args[0][0]
        self.assertIn("am start", cmd)
        self.assertIn("io.stamethyst.debug", cmd)
        self.assertIn("autoplay", cmd.lower())

    def test_status_checks_process(self):
        from scripts.tools.harness.orchestrator import HarnessOrchestrator
        self.mock_conn.shell.return_value = {
            "exit": 0, "stdout": "u0_a142 12345 ... sts", "stderr": ""}
        orch = HarnessOrchestrator(
            connector=self.mock_conn,
            application_id="io.stamethyst.debug",
        )
        status = orch.game_status()
        self.assertTrue(status["running"])

    def test_install_triggers_gradle_and_install(self):
        from scripts.tools.harness.orchestrator import HarnessOrchestrator
        orch = HarnessOrchestrator(
            connector=self.mock_conn,
            application_id="io.stamethyst.debug",
        )
        with patch("subprocess.check_call") as mock_check:
            orch.build_and_install()
            self.assertGreaterEqual(mock_check.call_count, 1)


if __name__ == "__main__":
    unittest.main()
