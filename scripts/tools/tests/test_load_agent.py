from __future__ import annotations

import json
import os
import subprocess
import tempfile
import time
import unittest

from scripts.tools.connector.client import ConnectorClient
from scripts.tools.lib.agent_client import AgentClient
from scripts.tools.lib.env_device import get_test_device_serial


def _start_daemon() -> ConnectorClient:
    proc = subprocess.Popen(
        ["python3", "-m", "scripts.tools.connector.daemon"],
        cwd=os.path.join(os.path.dirname(__file__), "..", "..", ".."),
        stdout=subprocess.PIPE,
        text=True,
    )
    info = json.loads(proc.stdout.readline().strip())
    time.sleep(0.3)
    client = ConnectorClient(port=info["port"])
    client.connect()
    client._daemon_proc = proc
    return client


def _stop_daemon(conn: ConnectorClient) -> None:
    try:
        conn.send_request({"method": "quit"})
    except Exception:
        pass
    conn.close()
    if hasattr(conn, "_daemon_proc"):
        conn._daemon_proc.wait(timeout=5)


class TestLoadAgentIntegration(unittest.TestCase):

    def setUp(self):
        self._conn = _start_daemon()
        self._conn.select(get_test_device_serial(), timeout_ms=10000)

    def tearDown(self):
        _stop_daemon(self._conn)

    def test_load_agent_success(self):
        if not get_test_device_serial() or get_test_device_serial() == "auto":
            self.skipTest("set STS_TEST_DEVICE to run real-device agent integration")
        """Verify LOAD_AGENT succeeds with a valid agent JAR on device."""
        test_jar_path = _build_test_agent_jar()
        self._conn.push(
            local=test_jar_path,
            remote="/data/data/io.stamethyst/files/test-agent.jar")

        self._conn.shell("am force-stop io.stamethyst")
        time.sleep(1)
        self._conn.shell(
            "am start -n io.stamethyst/.LauncherActivity"
            " --es io.stamethyst.debug_launch_mode mts"
            " --ez io.stamethyst.debug_autoplay true",
        )

        agent = AgentClient(connector=self._conn, port=9099)
        for _ in range(90):
            try:
                agent.connect()
                resp = agent.send("LIST")
                if resp and "MONITORS" in resp:
                    break
                agent.close()
            except Exception:
                pass
            time.sleep(2)
        else:
            self.fail("game-probe did not come up within 60s")

        try:
            resp = agent.send("LIST")
            self.assertIn("MONITORS", resp)

            agent.load_agent("/data/data/io.stamethyst/files/test-agent.jar")

        finally:
            agent.close()
            self._conn.shell("am force-stop io.stamethyst")

        os.unlink(test_jar_path)
        self._conn.shell("rm -f /data/data/io.stamethyst/files/test-agent.jar")


def _build_test_agent_jar() -> str:
    """Compile and JAR a minimal agent with agentmain(). Returns path."""
    java_dir = tempfile.mkdtemp(prefix="test-agent-")
    src = os.path.join(java_dir, "TestAgent.java")
    with open(src, "w") as f:
        f.write("""\
public class TestAgent {
    public static void agentmain(String args, java.lang.instrument.Instrumentation inst) {
        System.out.println("[test-agent] LOADED with args: " + args);
    }
}
""")
    java_home = sorted(
        [d for d in os.listdir(os.path.expanduser("~/tools"))
         if d.startswith("jdk-")],
        reverse=True)[0]
    java_home_path = os.path.join(os.path.expanduser("~/tools"), java_home)
    javac = os.path.join(java_home_path, "bin", "javac")
    jar = os.path.join(java_home_path, "bin", "jar")
    subprocess.check_call([javac, "-source", "8", "-target", "8", src])
    class_file = os.path.join(java_dir, "TestAgent.class")
    manifest_file = os.path.join(java_dir, "MANIFEST.MF")
    with open(manifest_file, "w") as f:
        f.write("Agent-Class: TestAgent\n")
    jar_path = os.path.join(java_dir, "test-agent.jar")
    subprocess.check_call([
        jar, "cfm", jar_path, manifest_file,
        "-C", java_dir, "TestAgent.class",
    ])
    return jar_path


if __name__ == "__main__":
    unittest.main()
