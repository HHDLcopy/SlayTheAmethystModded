from __future__ import annotations

import unittest
from unittest.mock import MagicMock

from scripts.tools.connector.client import Stream


class TestArthasShell(unittest.TestCase):

    def test_send_command_returns_output_before_next_prompt(self):
        from scripts.tools.arthas.shell import ArthasShell
        mock_sock = MagicMock()
        mock_sock.recv.side_effect = [
            b"[arthas@1]$ ",
            OSError(),
            b"3.6.9\n[arthas@1]$ ",
            b"",
        ]
        stream = Stream(sock=mock_sock, stream_id="s1")
        shell = ArthasShell(stream=stream)
        result = shell.command("version")
        self.assertEqual(result, "3.6.9")

    def test_command_strips_prompt_from_multi_line_output(self):
        from scripts.tools.arthas.shell import ArthasShell
        mock_sock = MagicMock()
        mock_sock.recv.side_effect = [
            b"[arthas@1]$ ",
            OSError(),
            b"line1\nline2\n[arthas@1]$ ",
            b"",
        ]
        stream = Stream(sock=mock_sock, stream_id="s1")
        shell = ArthasShell(stream=stream)
        result = shell.command("mycmd")
        self.assertEqual(result, "line1\nline2")

    def test_drain_prompt_loops_until_socket_empty(self):
        from scripts.tools.arthas.shell import ArthasShell
        mock_sock = MagicMock()
        mock_sock.recv.side_effect = [b"stale1", b"stale2", Exception("timeout")]
        stream = Stream(sock=mock_sock, stream_id="s1")
        shell = ArthasShell(stream=stream)
        shell._drain_prompt()
        self.assertEqual(mock_sock.recv.call_count, 3)

    def test_command_retries_on_type_not_present(self):
        from scripts.tools.arthas.shell import ArthasShell
        mock_sock1 = MagicMock()
        mock_sock2 = MagicMock()
        mock_sock1.recv.side_effect = [
            Exception("timeout"),           # drain
            b"Enhance error! java.lang.TypeNotPresentException\n[arthas@1]$ ",
            b"",
        ]
        mock_sock2.recv.side_effect = [
            Exception("timeout"),           # drain
            b"ok result\n[arthas@1]$ ",
            b"",
        ]
        stream1 = Stream(sock=mock_sock1, stream_id="s1")
        stream2 = Stream(sock=mock_sock2, stream_id="s2")
        stream1_closed = False

        def reconnect_fn():
            nonlocal stream1_closed
            stream1_closed = True
            return stream2

        shell = ArthasShell(stream=stream1, reconnect_fn=reconnect_fn)
        result = shell.command("trace Foo bar")
        self.assertTrue(stream1_closed)
        self.assertEqual(result, "ok result")
        self.assertIs(shell._sock, mock_sock2)

    def test_command_does_not_retry_twice(self):
        from scripts.tools.arthas.shell import ArthasShell
        mock_sock1 = MagicMock()
        mock_sock2 = MagicMock()
        mock_sock1.recv.side_effect = [
            Exception("timeout"),
            b"Enhance error! java.lang.TypeNotPresentException\n[arthas@1]$ ",
            b"",
        ]
        mock_sock2.recv.side_effect = [
            Exception("timeout"),
            b"Enhance error! java.lang.TypeNotPresentException again\n[arthas@1]$ ",
            b"",
        ]
        stream1 = Stream(sock=mock_sock1, stream_id="s1")
        stream2 = Stream(sock=mock_sock2, stream_id="s2")
        stream1_closed = False

        def reconnect_fn():
            nonlocal stream1_closed
            stream1_closed = True
            return stream2

        shell = ArthasShell(stream=stream1, reconnect_fn=reconnect_fn)
        result = shell.command("trace Foo bar")
        self.assertTrue(stream1_closed)
        self.assertIn("TypeNotPresentException", result)
