from __future__ import annotations

import unittest
from unittest.mock import MagicMock, patch

from scripts.tools.lib.agent_client import AgentClient


class TestAutoplayController(unittest.TestCase):

    def test_play_card_calls_execute(self):
        from scripts.tools.autoplay.controller import AutoplayController
        mock_agent = MagicMock(spec=AgentClient)
        mock_agent.execute.return_value = {"queued": True, "command": "PLAY_CARD"}
        ctrl = AutoplayController(mock_agent)
        result = ctrl.play_card()
        self.assertTrue(result["queued"])
        mock_agent.execute.assert_called_once_with("PLAY_CARD", {})

    def test_end_turn_calls_execute(self):
        from scripts.tools.autoplay.controller import AutoplayController
        mock_agent = MagicMock(spec=AgentClient)
        ctrl = AutoplayController(mock_agent)
        ctrl.end_turn()
        mock_agent.execute.assert_called_once_with("END_TURN", {})

    def test_set_mode_sends_mode_command(self):
        from scripts.tools.autoplay.controller import AutoplayController
        mock_agent = MagicMock(spec=AgentClient)
        ctrl = AutoplayController(mock_agent)
        ctrl.set_mode("COMMAND_DRIVEN")
        mock_agent.execute.assert_called_once_with(
            "MODE_COMMAND", {"mode": "COMMAND_DRIVEN"})

    def test_wait_sends_ms(self):
        from scripts.tools.autoplay.controller import AutoplayController
        mock_agent = MagicMock(spec=AgentClient)
        ctrl = AutoplayController(mock_agent)
        ctrl.wait(500)
        mock_agent.execute.assert_called_once_with(
            "WAIT", {"ms": 500})


class TestArthasManager(unittest.TestCase):

    def test_start_delegates_health_and_recovery_to_connector(self):
        from scripts.tools.arthas.manager import ArthasManager
        mock_conn = MagicMock()

        mgr = ArthasManager(connector=mock_conn, agent_client=None)
        mgr.start()

        mock_conn.arthas_ensure.assert_called_once_with(agent_port=9099, arthas_port=8099)

    def test_stop_delegates_reset_to_connector(self):
        from scripts.tools.arthas.manager import ArthasManager
        mock_conn = MagicMock()

        mgr = ArthasManager(connector=mock_conn, agent_client=None)
        mgr.stop()

        mock_conn.arthas_reset.assert_called_once_with(agent_port=9099, arthas_port=8099)


if __name__ == "__main__":
    unittest.main()
