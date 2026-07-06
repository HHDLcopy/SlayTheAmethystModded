from __future__ import annotations

import unittest
from unittest.mock import MagicMock, patch


class TestConnectorDeploy(unittest.TestCase):

    @patch("subprocess.check_call")
    def test_deploy_builds_and_pushes(self, mock_check_call):
        from scripts.tools.connector.deploy import deploy_agent_connector
        mock_conn = MagicMock()
        mock_conn.push.return_value = True
        mock_conn.shell.return_value = {"stdout": "/data/..."}
        deploy_agent_connector(connector=mock_conn, app_id="io.stamethyst")
        self.assertGreaterEqual(mock_check_call.call_count, 1)
        self.assertGreaterEqual(mock_conn.push.call_count, 1)
