from __future__ import annotations

import unittest


class TestDaemonProcessManagement(unittest.TestCase):

    def test_daemon_accepts_port_arg(self):
        from scripts.tools.connector.daemon import Daemon
        daemon = Daemon(port=15555)
        self.assertEqual(daemon._port, 15555)

    def test_daemon_default_port_is_none(self):
        from scripts.tools.connector.daemon import Daemon
        daemon = Daemon()
        self.assertIsNone(daemon._port)


if __name__ == "__main__":
    unittest.main()
