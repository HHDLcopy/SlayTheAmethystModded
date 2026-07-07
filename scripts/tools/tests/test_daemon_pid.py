from __future__ import annotations

import os
import tempfile
import unittest

from scripts.tools.connector.daemon import Daemon


class TestDaemonProcessManagement(unittest.TestCase):

    def test_daemon_token_is_random(self):
        daemon1 = Daemon()
        daemon2 = Daemon()
        self.assertNotEqual(daemon1._token, daemon2._token,
                            "each daemon instance should get a unique token")
        self.assertEqual(len(daemon1._token), 32)

    def test_daemon_writes_pid_file(self):
        tmpdir = tempfile.mkdtemp(prefix="sts-daemon-pid-")
        pid_path = os.path.join(tmpdir, "daemon.pid")

        daemon = Daemon(pid_file=pid_path)
        self.assertTrue(os.path.isfile(pid_path))
        pid = int(open(pid_path).read().strip())
        self.assertEqual(pid, os.getpid())

        daemon._running = False
        daemon.stop()
        self.assertFalse(os.path.isfile(pid_path))


if __name__ == "__main__":
    unittest.main()
