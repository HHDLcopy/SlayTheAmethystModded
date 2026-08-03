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

    def test_start_pushes_loads_and_forwards(self):
        from scripts.tools.arthas.manager import ArthasManager
        mock_conn = MagicMock()
        mock_agent = MagicMock(spec=AgentClient)
        mock_conn.push.return_value = True
        mock_conn.forward.return_value = {"ok": True, "port": 8563}

        mgr = ArthasManager(connector=mock_conn, agent_client=mock_agent)
        mgr.start()

        # The three JARs must be pushed: core + spy + bridge.  start() also
        # pushes native libs and companion assets, so assert on the JARs
        # themselves rather than a brittle total call count.
        pushed = [
            kwargs.get("remote", args[1] if len(args) > 1 else "")
            for args, kwargs in mock_conn.push.call_args_list
        ]
        for jar in ("arthas-core.jar", "arthas-spy.jar", "arthas-bridge.jar"):
            self.assertTrue(
                any(p.endswith(jar) for p in pushed), f"{jar} was not pushed"
            )
        # load_agent with correct arg format
        mock_agent.load_agent.assert_called_once()
        args_call = mock_agent.load_agent.call_args
        self.assertIn("arthas-bridge.jar", args_call[0][0])
        # One port forwarded: telnet
        self.assertEqual(mock_conn.forward.call_count, 1)

    def test_stop_unforwards_ports(self):
        from scripts.tools.arthas.manager import ArthasManager
        mock_conn = MagicMock()
        mock_agent = MagicMock(spec=AgentClient)
        mock_conn.unforward.return_value = True

        mgr = ArthasManager(connector=mock_conn, agent_client=mock_agent)
        # ArthasShell must be stubbed: a bare MagicMock stream makes
        # _drain_prompt() loop forever because read() never returns falsy.
        with patch("scripts.tools.arthas.manager.ArthasShell", return_value=MagicMock()):
            mgr.stop()

        self.assertEqual(mock_conn.unforward.call_count, 1)


if __name__ == "__main__":
    unittest.main()
