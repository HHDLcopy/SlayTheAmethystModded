from __future__ import annotations

import argparse

from .sts_harness import COMMANDS, LAUNCH_MODES, Harness, HarnessOptions


def create_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="SlayTheAmethyst Android debug harness.")
    parser.add_argument("-Command", "--command", dest="command", choices=COMMANDS, default="doctor")
    parser.add_argument("-LaunchMode", "--launch-mode", dest="launch_mode", choices=LAUNCH_MODES, default="mts_basemod")
    parser.add_argument("-DeviceSerial", "--device-serial", dest="device_serial", default="")
    parser.add_argument("-OutDir", "--out-dir", dest="out_dir", default="")
    parser.add_argument("-TimeoutSeconds", "--timeout-seconds", dest="timeout_seconds", type=int, default=None)
    parser.add_argument("-PollIntervalSeconds", "--poll-interval-seconds", dest="poll_interval_seconds", type=int, default=2)
    parser.add_argument("-ForceJvmCrash", "--force-jvm-crash", dest="force_jvm_crash", action="store_true")
    parser.add_argument("-ForceRuntimeCrash", "--force-runtime-crash", dest="force_runtime_crash", action="store_true")
    parser.add_argument("-Autoplay", "--autoplay", dest="autoplay", action="store_true")
    parser.add_argument("-SkipInstall", "--skip-install", dest="skip_install", action="store_true")
    parser.add_argument("-NoStopAfterSmoke", "--no-stop-after-smoke", dest="no_stop_after_smoke", action="store_true")
    parser.add_argument(
        "-Mods",
        "--mods",
        dest="mods",
        action="append",
        default=[],
        help="Comma- or newline-separated optional mod ids, jar names, display names, launch ids, or storage paths for set-mods. Repeatable.",
    )
    parser.add_argument(
        "-ModListFile",
        "--mod-list-file",
        dest="mod_list_file",
        default="",
        help="Local UTF-8 text file containing one optional mod token per line for set-mods. Lines starting with # are ignored.",
    )
    parser.add_argument("-EnableAllMods", "--enable-all-mods", dest="enable_all_mods", action="store_true")
    parser.add_argument("-DisableAllMods", "--disable-all-mods", dest="disable_all_mods", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = create_parser()
    args = parser.parse_args(argv)
    timeout_seconds = args.timeout_seconds
    if timeout_seconds is None:
        timeout_seconds = 300 if args.autoplay else 120
    options = HarnessOptions(
        command=args.command,
        launch_mode=args.launch_mode,
        device_serial=args.device_serial,
        out_dir=args.out_dir,
        timeout_seconds=timeout_seconds,
        poll_interval_seconds=args.poll_interval_seconds,
        force_jvm_crash=args.force_jvm_crash,
        force_runtime_crash=args.force_runtime_crash,
        autoplay=args.autoplay,
        skip_install=args.skip_install,
        no_stop_after_smoke=args.no_stop_after_smoke,
        mods=args.mods,
        mod_list_file=args.mod_list_file,
        enable_all_mods=args.enable_all_mods,
        disable_all_mods=args.disable_all_mods,
    )
    return Harness(options).run()
