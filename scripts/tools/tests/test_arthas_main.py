from __future__ import annotations

import os
import unittest
from unittest.mock import MagicMock, patch


class TestArthasDeviceResolution(unittest.TestCase):
    def test_parse_query_accepts_duration_before_command(self) -> None:
        from scripts.tools.arthas.__main__ import _parse_args

        args = _parse_args([
            "--device", "localhost:15555", "query", "--duration", "20",
            "monitor Foo bar",
        ])

        self.assertEqual(args.query_parts, ["monitor Foo bar"])
        self.assertEqual(args.duration, 20.0)

    def test_resolve_device_prefers_cli_device(self) -> None:
        from scripts.tools.arthas.__main__ import resolve_device

        conn = MagicMock()
        self.assertEqual("localhost:15555", resolve_device(conn, "localhost:15555"))
        conn.devices.assert_not_called()

    def test_resolve_device_uses_env_when_cli_missing(self) -> None:
        from scripts.tools.arthas.__main__ import resolve_device

        conn = MagicMock()
        with patch(
            "scripts.tools.arthas.__main__.get_test_device_serial",
            return_value="localhost:25555",
        ):
            self.assertEqual("localhost:25555", resolve_device(conn, None))
        conn.devices.assert_not_called()

    def test_resolve_device_auto_selects_single_online_device(self) -> None:
        from scripts.tools.arthas.__main__ import resolve_device

        conn = MagicMock()
        conn.devices.return_value = [
            {"serial": "localhost:15555", "state": "device"},
        ]
        with patch(
            "scripts.tools.arthas.__main__.get_test_device_serial",
            return_value="auto",
        ):
            self.assertEqual("localhost:15555", resolve_device(conn, None))

    def test_resolve_device_rejects_multiple_devices_without_explicit_serial(self) -> None:
        from scripts.tools.arthas.__main__ import resolve_device

        conn = MagicMock()
        conn.devices.return_value = [
            {"serial": "localhost:15555", "state": "device"},
            {"serial": "localhost:25555", "state": "device"},
        ]
        with patch(
            "scripts.tools.arthas.__main__.get_test_device_serial",
            return_value="auto",
        ):
            with self.assertRaises(SystemExit) as ctx:
                resolve_device(conn, None)
        self.assertIn("localhost:15555", str(ctx.exception))
        self.assertIn("localhost:25555", str(ctx.exception))


class TestArthasMainCommands(unittest.TestCase):
    def test_start_selects_explicit_device_and_closes_agent(self) -> None:
        from scripts.tools.arthas import __main__ as arthas_main

        conn = MagicMock()
        agent = MagicMock()
        mgr = MagicMock()
        with patch.object(arthas_main, "ConnectorClient", return_value=conn), patch.object(
            arthas_main, "AgentClient", return_value=agent
        ), patch.object(arthas_main, "ArthasManager", return_value=mgr), patch.object(
            arthas_main, "resolve_device", return_value="localhost:15555"
        ):
            code = arthas_main.main(["--device", "localhost:15555", "start"])

        self.assertEqual(0, code)
        conn.connect.assert_called_once()
        conn.select.assert_called_once_with("localhost:15555")
        agent.connect.assert_called_once()
        mgr.start.assert_called_once()
        agent.close.assert_called_once()
        conn.close.assert_called_once()

    def test_query_selects_explicit_device_and_cleans_up(self) -> None:
        from scripts.tools.arthas import __main__ as arthas_main

        conn = MagicMock()
        stream = MagicMock()
        conn.connect_stream.return_value = stream
        with patch.object(arthas_main, "ConnectorClient", return_value=conn), patch.object(
            arthas_main, "resolve_device", return_value="localhost:25555"
        ), patch.object(arthas_main, "run_query") as run_query:
            code = arthas_main.main(
                ["--device", "localhost:25555", "query", "version"]
            )

        self.assertEqual(0, code)
        conn.select.assert_called_once_with("localhost:25555")
        conn.forward.assert_called_once_with(port=8099)
        run_query.assert_called_once()
        self.assertEqual("version", run_query.call_args.args[1])
        stream.close.assert_called_once()
        conn.unforward.assert_called_once_with(port=8099)
        conn.close.assert_called_once()

    def test_query_cleans_up_on_failure(self) -> None:
        from scripts.tools.arthas import __main__ as arthas_main

        conn = MagicMock()
        stream = MagicMock()
        conn.connect_stream.return_value = stream
        with patch.object(arthas_main, "ConnectorClient", return_value=conn), patch.object(
            arthas_main, "resolve_device", return_value="localhost:15555"
        ), patch.object(arthas_main, "run_query", side_effect=RuntimeError("boom")):
            with self.assertRaises(RuntimeError):
                arthas_main.main(["--device", "localhost:15555", "query", "version"])

        stream.close.assert_called_once()
        conn.unforward.assert_called_once_with(port=8099)
        conn.close.assert_called_once()

    def test_reconnect_keeps_resolved_device(self) -> None:
        from scripts.tools.arthas import __main__ as arthas_main

        conn = MagicMock()
        stream = MagicMock()
        conn.connect_stream.return_value = stream
        captured = {}

        def capture_run_query(stream_arg, command, reconnect_fn=None, stdout=None):
            captured["reconnect_fn"] = reconnect_fn

        with patch.object(arthas_main, "ConnectorClient", return_value=conn), patch.object(
            arthas_main, "resolve_device", return_value="localhost:15555"
        ), patch.object(arthas_main, "run_query", side_effect=capture_run_query):
            arthas_main.main(["--device", "localhost:15555", "query", "trace Foo bar"])

        reconnect = captured["reconnect_fn"]
        self.assertIsNotNone(reconnect)
        reconnect_conn = MagicMock()
        reconnect_stream = MagicMock()
        reconnect_conn.connect_stream.return_value = reconnect_stream
        with patch.object(arthas_main, "ConnectorClient", return_value=reconnect_conn):
            result = reconnect()

        self.assertIs(result, reconnect_stream)
        reconnect_conn.select.assert_called_once_with("localhost:15555")
        reconnect_conn.forward.assert_called_once_with(port=8099)

    def test_stop_selects_device_and_invokes_manager_stop(self) -> None:
        from scripts.tools.arthas import __main__ as arthas_main

        conn = MagicMock()
        mgr = MagicMock()
        with patch.object(arthas_main, "ConnectorClient", return_value=conn), patch.object(
            arthas_main, "ArthasManager", return_value=mgr
        ), patch.object(arthas_main, "resolve_device", return_value="localhost:15555"):
            code = arthas_main.main(["--device", "localhost:15555", "stop"])

        self.assertEqual(0, code)
        conn.select.assert_called_once_with("localhost:15555")
        mgr.stop.assert_called_once()
        conn.close.assert_called_once()


class TestArthasManagerStop(unittest.TestCase):
    def test_stop_sends_reset_and_stop_before_unforward(self) -> None:
        from scripts.tools.arthas.manager import ArthasManager

        conn = MagicMock()
        stream = MagicMock()
        conn.connect_stream.return_value = stream
        shell = MagicMock()
        mgr = ArthasManager(connector=conn, agent_client=None)
        with patch("scripts.tools.arthas.manager.ArthasShell", return_value=shell):
            mgr.stop(port=8099)

        conn.forward.assert_called_once_with(port=8099)
        shell.command.assert_any_call("reset")
        shell.command.assert_any_call("stop")
        stream.close.assert_called_once()
        conn.unforward.assert_called_once_with(port=8099)

    def test_stop_is_idempotent_when_bridge_unavailable(self) -> None:
        from scripts.tools.arthas.manager import ArthasManager

        conn = MagicMock()
        conn.forward.side_effect = RuntimeError("bridge down")
        mgr = ArthasManager(connector=conn, agent_client=None)
        mgr.stop(port=8099)
        conn.unforward.assert_called_once_with(port=8099)


if __name__ == "__main__":
    unittest.main()
