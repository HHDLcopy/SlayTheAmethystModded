import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from scripts.tools.lib.sts_harness import HarnessOptions
from scripts.tools.harness._context import HarnessContext
from scripts.tools.harness._device import (
    clear_runtime_signals,
    device_logcat_timestamp,
    parse_remote_path_state_output,
    read_remote_sts_text,
    remote_sts_root_script,
    resolve_device_sts_root,
)


class ResolveDeviceStsRootTest(unittest.TestCase):
    def _make_ctx(self, application_id="com.example.app"):
        return HarnessContext(
            options=HarnessOptions(
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
            ),
            repo_root=Path("/fake/repo"),
            application_id=application_id,
        )

    @patch("scripts.tools.harness._device.adb_shell_script")
    @patch("scripts.tools.harness._device.adb")
    def test_shell_candidate_found(self, mock_adb, mock_shell):
        mock_shell.return_value = MagicMock(exit_code=0)
        ctx = self._make_ctx()
        result = resolve_device_sts_root(ctx)
        self.assertEqual(result["accessMode"], "shell")
        self.assertIn("Android/data/com.example.app/files/sts", result["root"])

    @patch("scripts.tools.harness._device.adb_shell_script")
    @patch("scripts.tools.harness._device.adb")
    def test_falls_back_to_run_as(self, mock_adb, mock_shell):
        mock_shell.return_value = MagicMock(exit_code=1)
        mock_adb.return_value = MagicMock(exit_code=0)
        ctx = self._make_ctx()
        result = resolve_device_sts_root(ctx)
        self.assertEqual(result["accessMode"], "run-as")
        self.assertEqual(result["root"], "files/sts")

    @patch("scripts.tools.harness._device.adb_shell_script")
    @patch("scripts.tools.harness._device.adb")
    def test_defaults_to_shell_on_both_fail(self, mock_adb, mock_shell):
        mock_shell.return_value = MagicMock(exit_code=1)
        mock_adb.return_value = MagicMock(exit_code=1)
        ctx = self._make_ctx()
        result = resolve_device_sts_root(ctx)
        self.assertEqual(result["accessMode"], "shell")


class ReadRemoteStsTextTest(unittest.TestCase):
    def _make_ctx(self):
        return HarnessContext(
            options=HarnessOptions(
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
            ),
            repo_root=Path("/fake/repo"),
            application_id="com.example.app",
        )

    @patch("scripts.tools.harness._device.adb_shell_script")
    def test_shell_mode_cat(self, mock_shell):
        mock_shell.return_value = MagicMock(exit_code=0, output="hello world")
        ctx = self._make_ctx()
        result = read_remote_sts_text(ctx, {"root": "/data/sts", "accessMode": "shell"}, "latest.log")
        self.assertEqual(result, "hello world")

    @patch("scripts.tools.harness._device.adb")
    def test_run_as_mode_cat(self, mock_adb):
        mock_adb.return_value = MagicMock(exit_code=0, output="run-as content")
        ctx = self._make_ctx()
        result = read_remote_sts_text(ctx, {"root": "files/sts", "accessMode": "run-as"}, "latest.log")
        self.assertEqual(result, "run-as content")

    @patch("scripts.tools.harness._device.adb_shell_script")
    def test_tail_lines(self, mock_shell):
        mock_shell.return_value = MagicMock(exit_code=0, output="line99\nline100")
        ctx = self._make_ctx()
        result = read_remote_sts_text(ctx, {"root": "/data/sts", "accessMode": "shell"}, "latest.log", tail_lines=2)
        self.assertEqual(result, "line99\nline100")


class ParseRemotePathStateOutputTest(unittest.TestCase):
    def test_parses_exists_true(self):
        result = parse_remote_path_state_output("latest.log", "exists=1\ntype=file\nbytes=42\nmtimeEpochSeconds=1234567890")
        self.assertTrue(result["exists"])
        self.assertEqual(result["type"], "file")
        self.assertEqual(result["bytes"], 42)
        self.assertEqual(result["mtimeEpochSeconds"], 1234567890)

    def test_parses_exists_false(self):
        result = parse_remote_path_state_output("missing.log", "exists=0")
        self.assertFalse(result["exists"])
        self.assertIsNone(result["type"])

    def test_parses_directory_with_children(self):
        result = parse_remote_path_state_output("dir", "exists=1\ntype=directory\nbytes=0\nchildCount=5\njarCount=2")
        self.assertEqual(result["childCount"], 5)
        self.assertEqual(result["jarCount"], 2)

    def test_handles_none_input(self):
        result = parse_remote_path_state_output("x", None)
        self.assertFalse(result["exists"])


class ClearRuntimeSignalsTest(unittest.TestCase):
    def _make_ctx(self):
        return HarnessContext(
            options=HarnessOptions(
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
            ),
            repo_root=Path("/fake/repo"),
            application_id="com.example.app",
        )

    @patch("scripts.tools.harness._device.resolve_device_sts_root")
    @patch("scripts.tools.harness._device.adb_shell_script")
    def test_clears_in_shell_mode(self, mock_shell, mock_root):
        mock_root.return_value = {"root": "/data/sts", "accessMode": "shell"}
        ctx = self._make_ctx()
        clear_runtime_signals(ctx)
        self.assertEqual(mock_shell.call_count, 2)

    @patch("scripts.tools.harness._device.resolve_device_sts_root")
    @patch("scripts.tools.harness._device.adb")
    def test_clears_in_run_as_mode(self, mock_adb, mock_root):
        mock_root.return_value = {"root": "files/sts", "accessMode": "run-as"}
        ctx = self._make_ctx()
        clear_runtime_signals(ctx)
        self.assertEqual(mock_adb.call_count, 2)


class RemoteStsRootScriptTest(unittest.TestCase):
    def _make_ctx(self):
        return HarnessContext(
            options=HarnessOptions(
                command="smoke", launch_mode="mts_basemod", device_serial="",
                out_dir="", timeout_seconds=300, poll_interval_seconds=2,
                force_jvm_crash=False, force_runtime_crash=False, debug_mode=False,
                autoplay=False, skip_install=False, no_stop_after_smoke=False,
                mods=[], mod_list_file="", enable_all_mods=False, disable_all_mods=False,
            ),
            repo_root=Path("/fake/repo"),
            application_id="com.example.app",
        )

    @patch("scripts.tools.harness._device.adb_shell_script")
    def test_shell_mode(self, mock_shell):
        mock_shell.return_value = MagicMock(exit_code=0, output="ok")
        ctx = self._make_ctx()
        result = remote_sts_root_script(ctx, {"root": "/data/sts", "accessMode": "shell"}, "echo hi")
        self.assertEqual(result.exit_code, 0)

    @patch("scripts.tools.harness._device.adb")
    def test_run_as_mode(self, mock_adb):
        mock_adb.return_value = MagicMock(exit_code=0, output="ok")
        ctx = self._make_ctx()
        result = remote_sts_root_script(ctx, {"root": "files/sts", "accessMode": "run-as"}, "echo hi")
        self.assertEqual(result.exit_code, 0)


class DeviceLogcatTimestampTest(unittest.TestCase):
    @patch("scripts.tools.harness._device.adb_shell_script")
    def test_valid_timestamp_parsed(self, mock_shell):
        mock_shell.return_value = MagicMock(exit_code=0, output="07-10 14:30:25.000")
        ctx = HarnessContext(
            options=HarnessOptions(
                command="smoke", launch_mode="mts_basemod", device_serial="",
                out_dir="", timeout_seconds=300, poll_interval_seconds=2,
                force_jvm_crash=False, force_runtime_crash=False, debug_mode=False,
                autoplay=False, skip_install=False, no_stop_after_smoke=False,
                mods=[], mod_list_file="", enable_all_mods=False, disable_all_mods=False,
            ),
            repo_root=Path("/fake/repo"),
        )
        result = device_logcat_timestamp(ctx)
        self.assertEqual(result, "07-10 14:30:25.000")

    @patch("scripts.tools.harness._device.adb_shell_script")
    def test_invalid_timestamp_returns_empty(self, mock_shell):
        mock_shell.return_value = MagicMock(exit_code=0, output="garbage")
        ctx = HarnessContext(
            options=HarnessOptions(
                command="smoke", launch_mode="mts_basemod", device_serial="",
                out_dir="", timeout_seconds=300, poll_interval_seconds=2,
                force_jvm_crash=False, force_runtime_crash=False, debug_mode=False,
                autoplay=False, skip_install=False, no_stop_after_smoke=False,
                mods=[], mod_list_file="", enable_all_mods=False, disable_all_mods=False,
            ),
            repo_root=Path("/fake/repo"),
        )
        result = device_logcat_timestamp(ctx)
        self.assertEqual(result, "")

    @patch("scripts.tools.harness._device.adb_shell_script")
    def test_failure_returns_empty(self, mock_shell):
        mock_shell.return_value = MagicMock(exit_code=1, output="")
        ctx = HarnessContext(
            options=HarnessOptions(
                command="smoke", launch_mode="mts_basemod", device_serial="",
                out_dir="", timeout_seconds=300, poll_interval_seconds=2,
                force_jvm_crash=False, force_runtime_crash=False, debug_mode=False,
                autoplay=False, skip_install=False, no_stop_after_smoke=False,
                mods=[], mod_list_file="", enable_all_mods=False, disable_all_mods=False,
            ),
            repo_root=Path("/fake/repo"),
        )
        result = device_logcat_timestamp(ctx)
        self.assertEqual(result, "")
