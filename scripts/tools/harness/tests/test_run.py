import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from scripts.tools.harness._context import HarnessContext
from scripts.tools.harness.run import run_start, run_stop


class HarnessRunTest(unittest.TestCase):
    def _ctx(self):
        return HarnessContext(
            options=SimpleNamespace(
                launch_mode="mts_basemod",
                force_jvm_crash=False,
                force_runtime_crash=False,
                debug_mode=True,
                autoplay=False,
                autoplay_save_mode="fresh",
                autoplay_mode="normal",
                disable_card_obtain_effect_ownership_compat=False,
            ),
            repo_root=Path("."),
            resolved_device_serial="localhost:15555",
            result={},
        )

    @patch("scripts.tools.harness.run.gradle")
    def test_start_passes_device_serial_property(self, gradle):
        run_start(self._ctx())

        args = gradle.call_args.args[1]
        self.assertIn("-PdeviceSerial=localhost:15555", args)
        self.assertNotIn("-PandroidDeviceSerial=localhost:15555", args)

    @patch("scripts.tools.harness.run.gradle")
    def test_stop_passes_device_serial_property(self, gradle):
        run_stop(self._ctx())

        args = gradle.call_args.args[1]
        self.assertIn("-PdeviceSerial=localhost:15555", args)
        self.assertNotIn("-PandroidDeviceSerial=localhost:15555", args)


if __name__ == "__main__":
    unittest.main()
