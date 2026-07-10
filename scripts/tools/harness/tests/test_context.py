import unittest
from datetime import datetime, timezone
from pathlib import Path

from scripts.tools.lib.sts_harness import HarnessOptions
from scripts.tools.harness._context import HarnessContext


class HarnessContextCreateTest(unittest.TestCase):
    def _make_options(self):
        return HarnessOptions(
            command="smoke",
            launch_mode="mts_basemod",
            device_serial="",
            out_dir="",
            timeout_seconds=300,
            poll_interval_seconds=2,
            force_jvm_crash=False,
            force_runtime_crash=False,
            debug_mode=False,
            autoplay=False,
            skip_install=False,
            no_stop_after_smoke=False,
            mods=[],
            mod_list_file="",
            enable_all_mods=False,
            disable_all_mods=False,
        )

    def test_create_with_minimal_args(self):
        options = self._make_options()
        repo_root = Path("/fake/repo")
        ctx = HarnessContext(options=options, repo_root=repo_root)
        self.assertIs(ctx.options, options)
        self.assertEqual(ctx.repo_root, repo_root)

    def test_fields_have_defaults(self):
        options = self._make_options()
        repo_root = Path("/fake/repo")
        ctx = HarnessContext(options=options, repo_root=repo_root)
        self.assertIsNone(ctx.gradle_wrapper)
        self.assertIsNone(ctx.adb_path)
        self.assertIsNone(ctx.application_id)
        self.assertIsNone(ctx.cached_out_dir)
        self.assertEqual(ctx.resolved_device_serial, "")
        self.assertEqual(ctx.operations, [])
        self.assertEqual(ctx.result, {})

    def test_operations_is_mutable(self):
        options = self._make_options()
        ctx = HarnessContext(options=options, repo_root=Path("/fake/repo"))
        ctx.operations.append({"name": "test"})
        self.assertEqual(len(ctx.operations), 1)

    def test_result_is_mutable(self):
        options = self._make_options()
        ctx = HarnessContext(options=options, repo_root=Path("/fake/repo"))
        ctx.result["key"] = "value"
        self.assertEqual(ctx.result["key"], "value")

    def test_started_at_is_utc_datetime(self):
        options = self._make_options()
        ctx = HarnessContext(options=options, repo_root=Path("/fake/repo"))
        self.assertIsInstance(ctx.started_at, datetime)
        self.assertEqual(ctx.started_at.tzinfo, timezone.utc)

    def test_cached_out_dir_is_mutable(self):
        options = self._make_options()
        ctx = HarnessContext(options=options, repo_root=Path("/fake/repo"))
        ctx.cached_out_dir = Path("/tmp/out")
        self.assertEqual(ctx.cached_out_dir, Path("/tmp/out"))
