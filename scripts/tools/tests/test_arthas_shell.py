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
        result = shell.command("version")
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
        result = shell.command("version")
        self.assertTrue(stream1_closed)
        self.assertIn("TypeNotPresentException", result)

    def test_command_reports_closed_shell_without_output(self):
        from scripts.tools.arthas.shell import ArthasShell

        mock_sock = MagicMock()
        mock_sock.recv.side_effect = [Exception("timeout"), b""]
        stream = Stream(sock=mock_sock, stream_id="s1")
        shell = ArthasShell(stream=stream)

        with self.assertRaisesRegex(RuntimeError, "complete prompt"):
            shell.command("dashboard -n 1")

    def test_streaming_command_collects_until_duration_then_interrupts(self):
        from scripts.tools.arthas.shell import ArthasShell

        mock_sock = MagicMock()
        mock_sock.recv.side_effect = [
            Exception("no stale prompt"),
            b"Affect(class count: 1, method count: 1)\n",
            b"timestamp total\n",
            b"[arthas@1]$ ",
        ]
        stream = Stream(sock=mock_sock, stream_id="s1")
        shell = ArthasShell(stream=stream)

        result = shell.command("monitor Foo bar", duration=0)

        self.assertIn("Affect(class count: 1", result)
        mock_sock.sendall.assert_any_call(b"monitor Foo bar\n")
        mock_sock.sendall.assert_any_call(b"\x03")

    def test_partial_finite_output_timeout_is_not_success(self):
        from scripts.tools.arthas.shell import ArthasQueryTimeout, ArthasShell

        mock_sock = MagicMock()
        mock_sock.recv.side_effect = [Exception("no stale prompt"), b"partial", __import__("socket").timeout()]
        shell = ArthasShell(Stream(sock=mock_sock, stream_id="s1"))

        with self.assertRaises(ArthasQueryTimeout):
            shell.command("version", timeout=0.01)
