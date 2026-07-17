import os
import subprocess
import sys
import unittest
from pathlib import Path


class MainEntrypointTest(unittest.TestCase):
    def test_harness_help_runs_without_pythonpath(self):
        repo_root = Path(__file__).resolve().parents[3]
        env = dict(os.environ)
        env.pop("PYTHONPATH", None)

        result = subprocess.run(
            [sys.executable, "scripts/tools/main.py", "sts-harness", "--help"],
            cwd=repo_root,
            env=env,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )

        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("SlayTheAmethyst Android debug harness", result.stdout)


if __name__ == "__main__":
    unittest.main()
