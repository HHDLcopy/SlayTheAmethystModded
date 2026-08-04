from __future__ import annotations

import unittest
from unittest.mock import MagicMock


class TestArthasManager(unittest.TestCase):
    def test_start_delegates_health_and_recovery_to_daemon(self) -> None:
        from scripts.tools.arthas.manager import ArthasManager

        conn = MagicMock()
        ArthasManager(connector=conn, agent_client=None).start(port=18099)

        conn.arthas_ensure.assert_called_once_with(agent_port=9099, arthas_port=18099)

    def test_stop_delegates_reset_to_daemon(self) -> None:
        from scripts.tools.arthas.manager import ArthasManager

        conn = MagicMock()
        ArthasManager(connector=conn, agent_client=None).stop(port=18099)

        conn.arthas_reset.assert_called_once_with(agent_port=9099, arthas_port=18099)

    def test_shutdown_delegates_full_teardown_to_daemon(self) -> None:
        from scripts.tools.arthas.manager import ArthasManager

        conn = MagicMock()
        ArthasManager(connector=conn, agent_client=None).shutdown(port=18099)

        conn.arthas_shutdown.assert_called_once_with(arthas_port=18099)


if __name__ == "__main__":
    unittest.main()
