from __future__ import annotations

import unittest
from unittest.mock import ANY, MagicMock, patch

from scripts.tools.lib.agent_client import AgentClient


class TestLoadAgentClassLoaderIsolation(unittest.TestCase):

    def test_load_agent_with_agent_class_uses_isolated_classloader(self):
        client = self._mock_client("OK")
        client.load_agent("/tmp/test.jar", "some-args")
        sent = client._writer.write.call_args[0][0]
        self.assertIn("LOAD_AGENT /tmp/test.jar some-args", sent)

    def test_load_agent_no_args_works(self):
        client = self._mock_client("OK")
        client.load_agent("/tmp/test.jar")
        sent = client._writer.write.call_args[0][0]
        self.assertEqual(sent, "LOAD_AGENT /tmp/test.jar\n")

    @staticmethod
    def _mock_client(response_line: str) -> AgentClient:
        client = AgentClient()
        mock_reader = MagicMock()
        mock_writer = MagicMock()
        mock_sock = MagicMock()
        mock_reader.readline.return_value = response_line
        client._sock = mock_sock
        client._reader = mock_reader
        client._writer = mock_writer
        return client
