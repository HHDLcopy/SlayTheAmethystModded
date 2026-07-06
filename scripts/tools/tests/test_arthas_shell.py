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
            b"line1\nline2\n[arthas@1]$ ",
            b"",
        ]
        stream = Stream(sock=mock_sock, stream_id="s1")
        shell = ArthasShell(stream=stream)
        result = shell.command("mycmd")
        self.assertEqual(result, "line1\nline2")
