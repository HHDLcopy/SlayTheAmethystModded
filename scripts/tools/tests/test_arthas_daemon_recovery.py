from __future__ import annotations

import unittest
from unittest.mock import MagicMock, patch


class TestDaemonArthasRecovery(unittest.TestCase):
    def setUp(self) -> None:
        from scripts.tools.connector.daemon import Daemon

        self.daemon = Daemon(port=0, adb_path="adb")

    def test_ready_bridge_is_reused_without_loading_agent(self) -> None:
        with patch.object(self.daemon, "_probe_arthas", return_value="1234") as probe, \
                patch.object(self.daemon, "_recover_arthas") as recover:
            result = self.daemon._ensure_arthas("device-a", 9099, 8099)

        self.assertEqual(
            {"ok": True, "state": "ready", "pid": "1234", "recovered": False},
            result,
        )
        probe.assert_called_once_with("device-a", 8099)
        recover.assert_not_called()

    def test_missing_bridge_is_recovered_through_game_probe(self) -> None:
        with patch.object(self.daemon, "_probe_arthas", side_effect=OSError("refused")), \
                patch.object(self.daemon, "_recover_arthas", return_value="2345") as recover:
            result = self.daemon._ensure_arthas("device-a", 9099, 8099)

        self.assertEqual(
            {"ok": True, "state": "ready", "pid": "2345", "recovered": True},
            result,
        )
        recover.assert_called_once_with("device-a", 9099, 8099)

    def test_recovery_retries_until_bridge_is_ready(self) -> None:
        with patch.object(
            self.daemon,
            "_push_arthas_resources",
        ), patch.object(
            self.daemon,
            "_agent_command",
            side_effect=["OK", "OK"],
        ), patch.object(
            self.daemon,
            "_probe_arthas",
            side_effect=[OSError("starting"), OSError("starting"), "3456"],
        ), patch("scripts.tools.connector.daemon.time.sleep"):
            result = self.daemon._recover_arthas("device-a", 9099, 8099)

        self.assertEqual("3456", result)

    def test_missing_game_probe_reports_error_without_relaunch(self) -> None:
        with patch.object(self.daemon, "_probe_arthas", side_effect=OSError("refused")), \
                patch.object(self.daemon, "_recover_arthas", side_effect=OSError("probe refused")):
            result = self.daemon._ensure_arthas("device-a", 9099, 8099)

        self.assertIn("error", result)
        self.assertEqual(-32010, result["error"]["code"])
        self.assertIn("game-probe", result["error"]["message"])
        status = self.daemon._arthas_status("device-a")
        self.assertEqual("game_unavailable", status["state"])
        self.assertIn("probe refused", status["last_error"])

    def test_runtime_state_is_isolated_by_device_serial(self) -> None:
        first = self.daemon._arthas_runtime("device-a")
        second = self.daemon._arthas_runtime("device-b")

        first["pid"] = "111"
        self.assertIsNot(first, second)
        self.assertIsNot(first["lock"], second["lock"])
        self.assertIsNone(second["pid"])

    def test_arthas_connect_stream_ensures_before_passthrough(self) -> None:
        session = {"serial": "device-a"}
        conn = MagicMock()
        ready = {"ok": True, "state": "ready", "pid": "123", "recovered": False}
        with patch.object(self.daemon, "_ensure_arthas", return_value=ready) as ensure, \
                patch.object(self.daemon, "_connect_stream") as connect:
            result = self.daemon._dispatch(
                {"method": "arthas_connect_stream", "params": {}}, conn, session,
            )

        self.assertIsNone(result)
        ensure.assert_called_once_with("device-a", 9099, 8099)
        connect.assert_called_once()
        self.assertEqual(8099, connect.call_args.args[0]["params"]["port"])

    def test_each_connection_selects_its_own_device(self) -> None:
        conn = MagicMock()
        first = {"serial": None}
        second = {"serial": None}

        self.daemon._dispatch({"method": "select", "params": {"serial": "device-a"}}, conn, first)
        self.daemon._dispatch({"method": "select", "params": {"serial": "device-b"}}, conn, second)

        self.assertEqual("device-a", first["serial"])
        self.assertEqual("device-b", second["serial"])


if __name__ == "__main__":
    unittest.main()
