from __future__ import annotations

import unittest
from unittest.mock import MagicMock, patch

from scripts.tools.connector.client import Stream
from scripts.tools.lib.agent_client import AgentClient


class TestAgentClientConnectStream(unittest.TestCase):

    def test_connect_uses_connector_stream_when_connector_available(self):
        mock_stream = MagicMock(spec=Stream)
        mock_stream.stream_id = "s9099"
        mock_conn = MagicMock()
        mock_conn.connect_stream.return_value = mock_stream

        client = AgentClient(connector=mock_conn, port=9099)
        client.connect()

        mock_conn.connect_stream.assert_called_once_with(port=9099)
        self.assertEqual(client._stream, mock_stream)
