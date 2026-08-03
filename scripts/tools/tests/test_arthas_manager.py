from __future__ import annotations

import unittest
from unittest.mock import MagicMock, call, patch


class TestArthasManagerStop(unittest.TestCase):
    """stop() is a lightweight reset; the bridge backend stays alive."""

    def test_stop_sends_reset_only(self) -> None:
        from scripts.tools.arthas.manager import ArthasManager

        conn = MagicMock()
        stream = MagicMock()
        conn.connect_stream.return_value = stream
        shell = MagicMock()
        mgr = ArthasManager(connector=conn, agent_client=None)
        with patch("scripts.tools.arthas.manager.ArthasShell", return_value=shell):
            mgr.stop(port=8099)

        shell.command.assert_called_once_with("reset")
        self.assertNotIn(call("stop"), shell.command.call_args_list)

    def test_stop_closes_stream_and_unforwards(self) -> None:
        from scripts.tools.arthas.manager import ArthasManager

        conn = MagicMock()
        stream = MagicMock()
        conn.connect_stream.return_value = stream
        mgr = ArthasManager(connector=conn, agent_client=None)
        with patch("scripts.tools.arthas.manager.ArthasShell", return_value=MagicMock()):
            mgr.stop(port=8099)

        conn.forward.assert_called_once_with(port=8099)
        stream.close.assert_called_once()
        conn.unforward.assert_called_once_with(port=8099)

    def test_stop_does_not_wait_for_port_release(self) -> None:
        """The backend keeps listening, so stop() must not poll the port."""
        from scripts.tools.arthas.manager import ArthasManager

        conn = MagicMock()
        conn.connect_stream.return_value = MagicMock()
        mgr = ArthasManager(connector=conn, agent_client=None)
        with patch("scripts.tools.arthas.manager.ArthasShell", return_value=MagicMock()), \
                patch.object(ArthasManager, "_await_port_release") as await_release:
            mgr.stop(port=8099)

        await_release.assert_not_called()

    def test_stop_is_idempotent_when_bridge_unavailable(self) -> None:
        from scripts.tools.arthas.manager import ArthasManager

        conn = MagicMock()
        conn.forward.side_effect = RuntimeError("bridge down")
        mgr = ArthasManager(connector=conn, agent_client=None)
        mgr.stop(port=8099)
        conn.unforward.assert_called_once_with(port=8099)

    def test_stop_survives_failing_reset(self) -> None:
        from scripts.tools.arthas.manager import ArthasManager

        conn = MagicMock()
        stream = MagicMock()
        conn.connect_stream.return_value = stream
        shell = MagicMock()
        shell.command.side_effect = RuntimeError("no prompt")
        mgr = ArthasManager(connector=conn, agent_client=None)
        with patch("scripts.tools.arthas.manager.ArthasShell", return_value=shell):
            mgr.stop(port=8099)

        stream.close.assert_called_once()
        conn.unforward.assert_called_once_with(port=8099)


class TestArthasManagerShutdown(unittest.TestCase):
    """shutdown() is the full teardown: reset + stop + wait for port release."""

    def test_shutdown_sends_reset_then_stop(self) -> None:
        from scripts.tools.arthas.manager import ArthasManager

        conn = MagicMock()
        conn.connect_stream.return_value = MagicMock()
        shell = MagicMock()
        mgr = ArthasManager(connector=conn, agent_client=None)
        with patch("scripts.tools.arthas.manager.ArthasShell", return_value=shell), \
                patch.object(ArthasManager, "_await_port_release", return_value=True):
            mgr.shutdown(port=8099)

        self.assertEqual(
            [call("reset"), call("stop")], shell.command.call_args_list
        )

    def test_shutdown_closes_stream_and_unforwards(self) -> None:
        from scripts.tools.arthas.manager import ArthasManager

        conn = MagicMock()
        stream = MagicMock()
        conn.connect_stream.return_value = stream
        mgr = ArthasManager(connector=conn, agent_client=None)
        with patch("scripts.tools.arthas.manager.ArthasShell", return_value=MagicMock()), \
                patch.object(ArthasManager, "_await_port_release", return_value=True):
            mgr.shutdown(port=8099)

        stream.close.assert_called_once()
        conn.unforward.assert_any_call(port=8099)

    def test_shutdown_waits_for_port_release(self) -> None:
        from scripts.tools.arthas.manager import ArthasManager

        conn = MagicMock()
        conn.connect_stream.return_value = MagicMock()
        mgr = ArthasManager(connector=conn, agent_client=None)
        with patch("scripts.tools.arthas.manager.ArthasShell", return_value=MagicMock()), \
                patch.object(ArthasManager, "_await_port_release", return_value=True) as await_release:
            mgr.shutdown(port=8099, wait_timeout=4.0)

        await_release.assert_called_once_with(8099, 4.0)

    def test_shutdown_is_idempotent_when_bridge_unavailable(self) -> None:
        from scripts.tools.arthas.manager import ArthasManager

        conn = MagicMock()
        conn.forward.side_effect = RuntimeError("bridge down")
        mgr = ArthasManager(connector=conn, agent_client=None)
        with patch.object(ArthasManager, "_await_port_release", return_value=True):
            mgr.shutdown(port=8099)
        conn.unforward.assert_any_call(port=8099)


class TestAwaitPortRelease(unittest.TestCase):
    def test_returns_true_once_connect_fails(self) -> None:
        from scripts.tools.arthas.manager import ArthasManager

        conn = MagicMock()
        conn.connect_stream.side_effect = OSError("connection refused")
        mgr = ArthasManager(connector=conn, agent_client=None)

        self.assertTrue(mgr._await_port_release(8099, 5.0))

    def test_returns_true_after_listener_goes_away(self) -> None:
        from scripts.tools.arthas.manager import ArthasManager

        conn = MagicMock()
        probe = MagicMock()
        # Accepts once, then the listener is gone.
        conn.connect_stream.side_effect = [probe, OSError("connection refused")]
        mgr = ArthasManager(connector=conn, agent_client=None)

        with patch("scripts.tools.arthas.manager.time.sleep"):
            self.assertTrue(mgr._await_port_release(8099, 5.0))

        probe.close.assert_called_once()

    def test_returns_false_when_port_stays_open_past_timeout(self) -> None:
        from scripts.tools.arthas.manager import ArthasManager

        conn = MagicMock()
        conn.connect_stream.return_value = MagicMock()
        mgr = ArthasManager(connector=conn, agent_client=None)

        # Monotonic clock jumps past the deadline after the first probe.
        with patch("scripts.tools.arthas.manager.time.sleep"), \
                patch("scripts.tools.arthas.manager.time.monotonic",
                      side_effect=[0.0, 0.0, 99.0]):
            self.assertFalse(mgr._await_port_release(8099, 5.0))

    def test_probe_is_always_unforwarded(self) -> None:
        from scripts.tools.arthas.manager import ArthasManager

        conn = MagicMock()
        conn.connect_stream.side_effect = OSError("connection refused")
        mgr = ArthasManager(connector=conn, agent_client=None)

        mgr._await_port_release(8099, 5.0)
        conn.unforward.assert_called_with(port=8099)


if __name__ == "__main__":
    unittest.main()
