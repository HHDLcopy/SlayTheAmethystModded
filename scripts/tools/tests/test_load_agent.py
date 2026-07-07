from __future__ import annotations

import os
import subprocess
import tempfile
import time
import unittest

from scripts.tools.connector.client import ConnectorClient
from scripts.tools.lib.agent_client import AgentClient
from scripts.tools.lib.env_device import get_test_device_serial


class TestLoadAgentIntegration(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls._sock_path = f"/tmp/sts-loadagent-test-{os.getpid()}.sock"
        try:
            os.unlink(cls._sock_path)
        except OSError:
            pass
        cls._daemon_proc = subprocess.Popen(
            [
                "python3", "-m", "scripts.tools.connector.daemon",
                "--socket", cls._sock_path,
            ],
            cwd=os.path.join(os.path.dirname(__file__), "..", "..", ".."),
        )
        time.sleep(1)

    @classmethod
    def tearDownClass(cls):
        if cls._daemon_proc:
            cls._daemon_proc.terminate()
            cls._daemon_proc.wait(timeout=5)
        try:
            os.unlink(cls._sock_path)
        except OSError:
            pass

    def setUp(self):
        self._conn = ConnectorClient(socket_path=self._sock_path)
        self._conn.connect()
        self._conn.select(get_test_device_serial(), timeout_ms=10000)

    def tearDown(self):
        self._conn.close()

    def test_load_agent_success(self):
        """Verify LOAD_AGENT succeeds with a valid agent JAR on device."""
        # Build and push a minimal test agent JAR
        test_jar_path = _build_test_agent_jar()
        self._conn.push(
            local=test_jar_path,
            remote="/data/data/io.stamethyst/files/test-agent.jar")

        # Start game with game-probe
        self._conn.shell("am force-stop io.stamethyst")
        time.sleep(1)
        self._conn.shell(
            "am start -n io.stamethyst/.LauncherActivity"
            " --es io.stamethyst.debug_launch_mode mts"
            " --ez io.stamethyst.debug_autoplay true",
        )

        # Wait for game-probe to come up
        self._conn.forward(port=9099)
        agent = AgentClient(port=9099)
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

            # THE KEY TEST: load the test agent
            agent.load_agent("/data/data/io.stamethyst/files/test-agent.jar")

        finally:
            agent.close()
            self._conn.unforward(port=9099)
            self._conn.shell("am force-stop io.stamethyst")

        os.unlink(test_jar_path)
        self._conn.shell("rm -f /data/data/io.stamethyst/files/test-agent.jar")


def _build_test_agent_jar() -> str:
    """Compile and JAR a minimal agent with agentmain(). Returns path."""
    import subprocess
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
