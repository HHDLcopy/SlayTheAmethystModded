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
            b"hello world\n[arthas@1]$ ",
            b"",
        ]
        stream = Stream(sock=mock_sock, stream_id="s1")
        out = io.StringIO()
        run_query(stream=stream, command="mycmd", stdout=out)
        mock_sock.sendall.assert_called()
        self.assertIn("hello world", out.getvalue())
