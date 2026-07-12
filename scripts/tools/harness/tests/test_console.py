import json
import os
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from scripts.tools.lib.agent_bridge import AgentBridgeError
from scripts.tools.lib.agent_protocol import AgentProtocol
from scripts.tools.harness._context import HarnessContext
from scripts.tools.harness.console import run_console

TEST_DEVICE_SERIAL = os.environ["TEST_DEVICE_SERIAL"]


class ConsoleExecTest(unittest.TestCase):
    def _make_mock_proto(self, result):
        proto = MagicMock(spec=AgentProtocol)
        proto.console_exec.return_value = result
        return proto

    def _make_ctx(self, console_command=""):
        from scripts.tools.lib.sts_harness import HarnessOptions
        return HarnessContext(
            options=HarnessOptions(
                command="console", launch_mode="mts_basemod",
                device_serial=TEST_DEVICE_SERIAL, out_dir="", timeout_seconds=120,
                poll_interval_seconds=2, force_jvm_crash=False, force_runtime_crash=False,
                debug_mode=False, autoplay=False, skip_install=False,
                no_stop_after_smoke=False, mods=[], mod_list_file="",
                enable_all_mods=False, disable_all_mods=False,
                console_command=console_command,
            ),
            repo_root=MagicMock(),
            result={"artifacts": {}},
        )

    def _patch_connect(self):
        mock_conn = MagicMock()
        mock_conn.is_connected.return_value = True
        return patch(
            "scripts.tools.harness.console._connect_agent",
            return_value=mock_conn,
        ), patch(
            "scripts.tools.harness.console.AgentProtocol",
            return_value=self._make_mock_proto({"executed": True, "command": "gold 999", "output": "ok"}),
        )

    def test_one_shot_execute(self):
        ctx = self._make_ctx("gold 999")
        with self._patch_connect()[0] as mock_connect, self._patch_connect()[1] as mock_proto:
            run_console(ctx, Path("/tmp/test"))
            mock_connect.assert_called_once()
            mock_proto.return_value.console_exec.assert_called_once_with("gold 999")
            self.assertTrue(ctx.result["success"])
            self.assertEqual(ctx.result["status"], "CONSOLE_EXECUTED")

    def test_interactive_mode_when_no_command(self):
        ctx = self._make_ctx("")
        mock_proto = self._make_mock_proto({"executed": True, "command": "help", "output": "ok"})

        with patch("scripts.tools.harness.console._connect_agent", return_value=MagicMock()), \
             patch("scripts.tools.harness.console.AgentProtocol", return_value=mock_proto), \
             patch("scripts.tools.harness.console.input", side_effect=["help", "exit"]):
            run_console(ctx, Path("/tmp/test"))
            self.assertEqual(ctx.result["status"], "CONSOLE_SESSION_COMPLETE")

    def test_handles_agent_bridge_error(self):
        ctx = self._make_ctx("gold 999")
        mock_proto = MagicMock(spec=AgentProtocol)
        mock_proto.console_exec.side_effect = AgentBridgeError("connection failed")

        with patch("scripts.tools.harness.console._connect_agent", return_value=MagicMock()), \
             patch("scripts.tools.harness.console.AgentProtocol", return_value=mock_proto):
            run_console(ctx, Path("/tmp/test"))
            self.assertFalse(ctx.result["success"])
            self.assertEqual(ctx.result["status"], "ERROR")
            self.assertIn("connection failed", ctx.result["message"])

    def test_handles_generic_exception(self):
        ctx = self._make_ctx("gold 999")
        mock_proto = MagicMock(spec=AgentProtocol)
        mock_proto.console_exec.side_effect = RuntimeError("boom")

        with patch("scripts.tools.harness.console._connect_agent", return_value=MagicMock()), \
             patch("scripts.tools.harness.console.AgentProtocol", return_value=mock_proto):
            run_console(ctx, Path("/tmp/test"))
            self.assertFalse(ctx.result["success"])
            self.assertEqual(ctx.result["status"], "ERROR")
            self.assertIn("boom", ctx.result["message"])

    def test_cleans_up_connection_and_forward(self):
        ctx = self._make_ctx("")
        mock_conn = MagicMock()

        with patch("scripts.tools.harness.console._connect_agent", return_value=mock_conn), \
             patch("scripts.tools.harness.console.AgentProtocol", return_value=self._make_mock_proto({"executed": False, "error": "x"})), \
             patch("scripts.tools.harness.console.input", side_effect=["exit"]):
            run_console(ctx, Path("/tmp/test"))
            mock_conn.close.assert_called_once()
            mock_conn.remove_forward.assert_called_once()

    def test_error_does_not_prevent_cleanup(self):
        ctx = self._make_ctx("")
        mock_conn = MagicMock()

        with patch("scripts.tools.harness.console._connect_agent", return_value=mock_conn), \
             patch("scripts.tools.harness.console.AgentProtocol", return_value=self._make_mock_proto({"executed": False, "error": "x"})), \
             patch("scripts.tools.harness.console.input", side_effect=RuntimeError("input crash")):
            try:
                run_console(ctx, Path("/tmp/test"))
            except RuntimeError:
                pass
            mock_conn.close.assert_called_once()
            mock_conn.remove_forward.assert_called_once()


class AgentProtocolConsoleTest(unittest.TestCase):
    def _make_proto_with_response(self, response):
        mock_conn = MagicMock()
        mock_conn.is_connected.return_value = True
        mock_conn.send_command.return_value = response
        return AgentProtocol(mock_conn)

    def test_console_exec_success(self):
        proto = self._make_proto_with_response(
            'RESULT {"executed":true,"command":"gold 999","output":"ok"}'
        )
        result = proto.console_exec("gold 999")
        self.assertTrue(result["executed"])
        self.assertEqual(result["command"], "gold 999")
        self.assertEqual(result["output"], "ok")

    def test_console_exec_error_response(self):
        proto = self._make_proto_with_response("ERROR BaseMod not loaded")
        with self.assertRaises(AgentBridgeError):
            proto.console_exec("gold 999")

    def test_console_exec_unexpected_response(self):
        proto = self._make_proto_with_response("GARBAGE")
        result = proto.console_exec("gold 999")
        self.assertFalse(result["executed"])
        self.assertIn("unexpected response", result["error"])

    def test_console_exec_sends_correct_command(self):
        mock_conn = MagicMock()
        mock_conn.is_connected.return_value = True
        mock_conn.send_command.return_value = 'RESULT {"executed":true,"command":"unlock Ironclad","output":"ok"}'
        proto = AgentProtocol(mock_conn)
        proto.console_exec("unlock Ironclad")
        mock_conn.send_command.assert_called_once_with("CONSOLE unlock Ironclad")

    def test_console_exec_with_unicode(self):
        proto = self._make_proto_with_response(
            'RESULT {"executed":true,"command":"test","output":"\u00e9"}'
        )
        result = proto.console_exec("test")
        self.assertTrue(result["executed"])
        self.assertEqual(result["output"], "\u00e9")


if __name__ == "__main__":
    unittest.main()
