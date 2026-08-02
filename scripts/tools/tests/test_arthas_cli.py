from __future__ import annotations

import io
import unittest
from unittest.mock import MagicMock, patch

from scripts.tools.connector.client import Stream


class TestArthasCLI(unittest.TestCase):

    def test_query_sends_command_and_prints_output(self):
        from scripts.tools.arthas.cli import run_query
        mock_sock = MagicMock()
        mock_sock.recv.side_effect = [
            b"[arthas@1]$ ",
            OSError(),
            b"hello world\n[arthas@1]$ ",
            b"",
        ]
        stream = Stream(sock=mock_sock, stream_id="s1")
        out = io.StringIO()
        run_query(stream=stream, command="mycmd", stdout=out)
        mock_sock.sendall.assert_called()
        self.assertIn("hello world", out.getvalue())

    def test_query_passes_reconnect_fn_to_shell(self):
        from scripts.tools.arthas.cli import run_query
        mock_sock = MagicMock()
        stream = Stream(sock=mock_sock, stream_id="s1")
        out = io.StringIO()

        def reconnect_fn():
            return stream

        with patch("scripts.tools.arthas.cli.ArthasShell") as MockShell:
            mock_shell = MockShell.return_value
            mock_shell.command.return_value = "result"
            run_query(
                stream=stream,
                command="test",
                reconnect_fn=reconnect_fn,
                stdout=out,
            )
        MockShell.assert_called_once_with(
            stream=stream, reconnect_fn=reconnect_fn,
        )
        mock_shell.command.assert_called_once_with("test")
        self.assertIn("result", out.getvalue())

    def test_query_limits_bare_dashboard_to_one_sample(self):
        from scripts.tools.arthas.cli import run_query

        stream = Stream(sock=MagicMock(), stream_id="s1")
        out = io.StringIO()
        with patch("scripts.tools.arthas.cli.ArthasShell") as MockShell:
            mock_shell = MockShell.return_value
            mock_shell.command.return_value = "dashboard output"
            run_query(stream=stream, command="dashboard", stdout=out)

        mock_shell.command.assert_called_once_with("dashboard -n 1")
        self.assertIn("dashboard output", out.getvalue())

    def test_query_preserves_dashboard_options(self):
        from scripts.tools.arthas.cli import run_query

        stream = Stream(sock=MagicMock(), stream_id="s1")
        out = io.StringIO()
        with patch("scripts.tools.arthas.cli.ArthasShell") as MockShell:
            mock_shell = MockShell.return_value
            mock_shell.command.return_value = "dashboard output"
            run_query(
                stream=stream,
                command="dashboard -n 5",
                stdout=out,
            )

        mock_shell.command.assert_called_once_with("dashboard -n 5")
