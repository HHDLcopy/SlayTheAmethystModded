from __future__ import annotations

import os
import signal
import time
import unittest


class TestDaemonProcessManagement(unittest.TestCase):

    def test_daemon_creates_and_cleans_pid_file(self):
        pid_path = f"/tmp/sts-daemon-pid-{os.getpid()}"
        try:
            os.unlink(pid_path)
        except OSError:
            pass

        from scripts.tools.connector.daemon import Daemon
        daemon = Daemon(
            socket_path="/tmp/sts-daemon-test.sock",
            pid_file=pid_path,
        )
        self.assertTrue(os.path.isfile(pid_path))
        pid = int(open(pid_path).read().strip())
        self.assertEqual(pid, os.getpid())
        daemon.stop()
        time.sleep(0.5)
        self.assertFalse(os.path.isfile(pid_path))
