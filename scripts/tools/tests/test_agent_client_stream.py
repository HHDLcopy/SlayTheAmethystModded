from __future__ import annotations

import unittest
from unittest.mock import MagicMock

from scripts.tools.connector.client import Stream
from scripts.tools.lib.agent_client import AgentClient


class TestAgentClientStream(unittest.TestCase):

    def test_agent_client_with_stream_sends_line(self):
        from scripts.tools.connector.client import Stream
        mock_sock = MagicMock()
        mock_sock.recv.return_value = b""
        stream = Stream(sock=mock_sock, stream_id="s1")

        client = AgentClient(stream=stream)
        resp = client.send("LIST")
        mock_sock.sendall.assert_called_with(b"LIST\n")
