from __future__ import annotations

import json
import os
import shutil
import tempfile
import unittest
from dataclasses import dataclass
from pathlib import Path
from unittest.mock import MagicMock, patch

TEST_DEVICE_SERIAL = os.environ["TEST_DEVICE_SERIAL"]

from scripts.tools.lib.sts_harness import Harness, HarnessOptions, COMMANDS


@dataclass
class _MinimalOptions:
    command: str = ""
    launch_mode: str = "mts_basemod"
    device_serial: str = ""
    out_dir: str = ""
    timeout_seconds: int = 120
    poll_interval_seconds: int = 2
    force_jvm_crash: bool = False
    force_runtime_crash: bool = False
    autoplay: bool = False
    skip_install: bool = False
    no_stop_after_smoke: bool = False
    mods: list = ()
    mod_list_file: str = ""
    enable_all_mods: bool = False
    disable_all_mods: bool = False
    decompil_targets: tuple[str, ...] = ()

    def __post_init__(self):
        self.mods = list(self.mods)
        self.decompil_targets = list(self.decompil_targets)


class DecompilTargetParsingTest(unittest.TestCase):
    def test_parse_simple_class(self):
        from scripts.tools.lib.sts_harness import parse_decompil_target
        class_name, method_name = parse_decompil_target(
            "com.megacrit.cardcrawl.cards.AbstractCard"
        )
        self.assertEqual(class_name, "com.megacrit.cardcrawl.cards.AbstractCard")
        self.assertIsNone(method_name)

    def test_parse_class_with_method(self):
        from scripts.tools.lib.sts_harness import parse_decompil_target
        class_name, method_name = parse_decompil_target(
            "com.megacrit.cardcrawl.cards.AbstractCard#applyPowers"
        )
        self.assertEqual(class_name, "com.megacrit.cardcrawl.cards.AbstractCard")
        self.assertEqual(method_name, "applyPowers")

    def test_parse_method_with_descriptor(self):
        from scripts.tools.lib.sts_harness import parse_decompil_target
        class_name, method_name = parse_decompil_target(
            "com.megacrit.cardcrawl.cards.AbstractCard#applyPowers(Lcom/megacrit/entities/Entity;)V"
        )
        self.assertEqual(class_name, "com.megacrit.cardcrawl.cards.AbstractCard")
        self.assertEqual(method_name, "applyPowers(Lcom/megacrit/entities/Entity;)V")

    def test_parse_empty_string_raises(self):
        from scripts.tools.lib.sts_harness import parse_decompil_target
        with self.assertRaises(ValueError):
            parse_decompil_target("")

    def test_parse_whitespace_only_raises(self):
        from scripts.tools.lib.sts_harness import parse_decompil_target
        with self.assertRaises(ValueError):
            parse_decompil_target("   ")


class DecompilCommandTest(unittest.TestCase):
    def test_decompil_in_commands(self):
        self.assertIn("decompil", COMMANDS)

    def test_decompil_routing_triggers_harness_decompil(self):
        options = HarnessOptions(
            command="decompil",
            launch_mode="mts_basemod",
            device_serial="",
            out_dir="",
            timeout_seconds=120,
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
            decompil_targets=["com.megacrit.cardcrawl.cards.AbstractCard"],
        )
        harness = Harness(options)
        harness.initialize = MagicMock()
        harness.run_command = lambda out_dir: 0  # noqa — bypass for this test
        self.assertEqual(harness.options.decompil_targets, ["com.megacrit.cardcrawl.cards.AbstractCard"])

    def test_decompil_empty_targets_raises(self):
        options = HarnessOptions(
            command="decompil",
            launch_mode="mts_basemod",
            device_serial="",
            out_dir="",
            timeout_seconds=120,
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
            decompil_targets=[],
        )
        harness = Harness(options)
        harness.initialize = MagicMock()
        harness.resolved_out_dir = MagicMock(return_value=unittest.mock.MagicMock())
        with self.assertRaises(ValueError):
            harness.run_command(harness.resolved_out_dir())


class CfrDownloadTest(unittest.TestCase):
    def _make_cfr_path_mock(self, exists=True, size=2000000):
        mock = MagicMock()
        mock.exists.return_value = exists

        class _Stat:
            st_size = size

        mock.stat.return_value = _Stat()
        return mock

    def _make_ctx(self):
        from scripts.tools.lib.sts_harness import HarnessOptions
        from scripts.tools.harness._context import HarnessContext
        from pathlib import Path
        return HarnessContext(
            options=HarnessOptions(
                command="decompil", launch_mode="mts_basemod", device_serial="",
                out_dir="", timeout_seconds=120, poll_interval_seconds=2,
                force_jvm_crash=False, force_runtime_crash=False, debug_mode=False,
                autoplay=False, skip_install=False, no_stop_after_smoke=False,
                mods=[], mod_list_file="", enable_all_mods=False, disable_all_mods=False,
                decompil_targets=["com.example.Foo"],
            ),
            repo_root=MagicMock(),
        )

    def test_ensure_cfr_returns_existing_jar(self):
        from scripts.tools.harness.decompil import _ensure_cfr
        ctx = self._make_ctx()
        fake_cfr_path = self._make_cfr_path_mock(exists=True)
        ctx.repo_root = MagicMock()
        ctx.repo_root.__truediv__.return_value.__truediv__.return_value.__truediv__.return_value.__truediv__.return_value = fake_cfr_path

        with patch("scripts.tools.harness.decompil.urllib.request.urlretrieve") as mock_download:
            result = _ensure_cfr(ctx)
            self.assertEqual(result, fake_cfr_path)
            mock_download.assert_not_called()

    def test_ensure_cfr_downloads_when_missing(self):
        from scripts.tools.harness.decompil import _ensure_cfr
        ctx = self._make_ctx()
        fake_cfr_path = self._make_cfr_path_mock()
        fake_cfr_path.exists.side_effect = [False, True]
        ctx.repo_root = MagicMock()
        ctx.repo_root.__truediv__.return_value.__truediv__.return_value.__truediv__.return_value.__truediv__.return_value = fake_cfr_path

        with patch("scripts.tools.harness.decompil.urllib.request.urlretrieve") as mock_download:
            result = _ensure_cfr(ctx)
            self.assertEqual(result, fake_cfr_path)
            mock_download.assert_called_once()

    def test_ensure_cfr_raises_on_download_failure(self):
        from scripts.tools.harness.decompil import _ensure_cfr
        ctx = self._make_ctx()
        fake_cfr_path = self._make_cfr_path_mock(exists=False, size=0)
        ctx.repo_root = MagicMock()
        ctx.repo_root.__truediv__.return_value.__truediv__.return_value.__truediv__.return_value.__truediv__.return_value = fake_cfr_path

        with patch("scripts.tools.harness.decompil.urllib.request.urlretrieve",
                   side_effect=Exception("network error")):
            with self.assertRaises(RuntimeError) as exc_ctx:
                _ensure_cfr(ctx)
            self.assertIn("Failed to download CFR", str(exc_ctx.exception))


class DecompilRoutingTest(unittest.TestCase):
    def test_decompil_run_command_sets_result(self):
        from scripts.tools.lib.sts_harness import Harness, HarnessOptions
        from unittest.mock import MagicMock

        options = HarnessOptions(
            command="decompil",
            launch_mode="mts_basemod",
            device_serial="",
            out_dir="",
            timeout_seconds=120,
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
            decompil_targets=["com.example.Foo"],
        )
        harness = Harness(options)
        harness.initialize = MagicMock()
        harness.resolved_out_dir = MagicMock()
        out = harness.resolved_out_dir.return_value = MagicMock()

        fake_info = {"decompiledClasses": ["Foo.java"]}
        with unittest.mock.patch(
            "scripts.tools.harness.decompil.run_decompil",
            return_value=(fake_info, True, "OK", "1 class decompiled"),
        ):
            harness.run_command(out)
            self.assertEqual(harness.result["decompilInfo"], fake_info)
            self.assertTrue(harness.result["success"])
            self.assertEqual(harness.result["status"], "OK")


class StartupCacheProfileTest(unittest.TestCase):
    def _make_options(self, **overrides):
        values = dict(
            command="startup-cache-profile",
            launch_mode="mts_basemod",
            device_serial="",
            out_dir="",
            timeout_seconds=300,
            poll_interval_seconds=2,
            force_jvm_crash=False,
            force_runtime_crash=False,
            debug_mode=False,
            autoplay=False,
            skip_install=True,
            no_stop_after_smoke=False,
            mods=[],
            mod_list_file="",
            enable_all_mods=False,
            disable_all_mods=False,
        )
        values.update(overrides)
        return HarnessOptions(**values)

    def test_command_is_registered(self):
        self.assertIn("startup-cache-profile", COMMANDS)

    def test_run_command_routes_to_startup_cache_profile(self):
        harness = Harness(self._make_options())
        out = Path(tempfile.mkdtemp())
        try:
            harness.result = {"artifacts": {}}
            with unittest.mock.patch(
                "scripts.tools.harness.startup_cache.run_startup_cache_profile",
                return_value=0,
            ) as mock_func:
                exit_code = harness.run_command(out)
                self.assertEqual(exit_code, 0)
                mock_func.assert_called_once()
        finally:
            shutil.rmtree(out, ignore_errors=True)

    def test_extract_startup_cache_log_evidence_detects_build(self):
        from scripts.tools.harness._status import extract_startup_cache_log_evidence
        text = "\n".join([
            "[Amethyst] Patch cache miss: marker changed",
            "[Amethyst] Writing MTS patch cache jar: /tmp/desktop-1.0-modded.jar",
            "[Amethyst] MTS patch cache step invokePackageJar cacheBytes=123 packageJars=21 took 4567ms",
            "[Amethyst] Wrote cached MTS annotation DB entries=12 took 34ms",
            "[Amethyst] MTS patch cache is ready: packageJars=21",
        ])
        evidence = extract_startup_cache_log_evidence(text)
        self.assertEqual(evidence["mode"], "cache-build")
        self.assertTrue(evidence["sawCacheBuild"])
        self.assertTrue(evidence["sawCacheMiss"])
        elapsed_values = [item["elapsedMs"] for item in evidence["timings"]]
        self.assertIn(4567, elapsed_values)
        self.assertIn(34, elapsed_values)

    def test_extract_startup_cache_log_evidence_detects_hit(self):
        from scripts.tools.harness._status import extract_startup_cache_log_evidence
        text = "\n".join([
            "[Amethyst] Launching cached MTS patch jar: /tmp/desktop-1.0-modded.jar",
            "[Amethyst] Prepared cached MTS prepackaged launch took 98ms",
            "[Amethyst] Restored cached MTS annotation DB: mods=20 entries=20 took 12ms",
        ])
        evidence = extract_startup_cache_log_evidence(text)
        self.assertEqual(evidence["mode"], "cache-hit")
        self.assertTrue(evidence["sawCacheHit"])
        self.assertGreaterEqual(len(evidence["timings"]), 2)

    def test_clear_startup_caches_clears_external_and_private_paths(self):
        from scripts.tools.harness._context import HarnessContext
        from scripts.tools.harness.startup_cache import clear_startup_caches
        ctx = HarnessContext(
            options=self._make_options(),
            repo_root=Path("/fake/repo"),
            application_id="io.test",
            result={"artifacts": {}},
        )
        with patch("scripts.tools.harness.startup_cache.resolve_device_sts_root",
                   return_value={"root": "/sdcard/Android/data/io.test/files/sts", "accessMode": "shell"}):
            external_result = MagicMock(exit_code=0, output="")
            private_result = MagicMock(exit_code=0, output="")
            with patch("scripts.tools.harness.startup_cache.remote_sts_root_script", return_value=external_result):
                with patch("scripts.tools.harness.startup_cache.adb", return_value=private_result):
                    summary = clear_startup_caches(ctx)
        self.assertEqual(summary["externalExitCode"], 0)
        self.assertEqual(summary["privateExitCode"], 0)



class ConsoleRoutingTest(unittest.TestCase):
    def test_console_command_is_registered(self):
        self.assertIn("console", COMMANDS)

    def test_console_run_command_routes(self):
        from scripts.tools.lib.sts_harness import Harness, HarnessOptions
        from unittest.mock import MagicMock

        options = HarnessOptions(
            command="console", launch_mode="mts_basemod", device_serial=TEST_DEVICE_SERIAL,
            out_dir="", timeout_seconds=120, poll_interval_seconds=2,
            force_jvm_crash=False, force_runtime_crash=False, debug_mode=False,
            autoplay=False, skip_install=False, no_stop_after_smoke=False,
            mods=[], mod_list_file="", enable_all_mods=False, disable_all_mods=False,
            console_command="gold 999",
        )
        harness = Harness(options)
        harness.initialize = MagicMock()
        out = MagicMock()
        harness.result = {"artifacts": {}}

        with unittest.mock.patch(
            "scripts.tools.harness.console.run_console",
        ) as mock_func:
            exit_code = harness.run_command(out)
            self.assertEqual(exit_code, 0)
            mock_func.assert_called_once()

    def test_console_command_without_console_command_arg(self):
        from scripts.tools.lib.sts_harness import Harness, HarnessOptions
        from unittest.mock import MagicMock

        options = HarnessOptions(
            command="console", launch_mode="mts_basemod", device_serial=TEST_DEVICE_SERIAL,
            out_dir="", timeout_seconds=120, poll_interval_seconds=2,
            force_jvm_crash=False, force_runtime_crash=False, debug_mode=False,
            autoplay=False, skip_install=False, no_stop_after_smoke=False,
            mods=[], mod_list_file="", enable_all_mods=False, disable_all_mods=False,
            console_command="",
        )
        harness = Harness(options)
        harness.initialize = MagicMock()
        out = MagicMock()
        harness.result = {"artifacts": {}}

        with unittest.mock.patch(
            "scripts.tools.harness.console.run_console",
        ) as mock_func:
            harness.run_command(out)
            mock_func.assert_called_once()



if __name__ == "__main__":
    unittest.main()
