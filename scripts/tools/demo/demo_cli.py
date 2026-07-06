"""CLI parser for the demo tool.  Mirrors sts_harness_cli.py pattern.

parse_args() → DemoOptions → DemoRunner(options).run()
"""

from __future__ import annotations

import argparse

from .demo_runner import ALL_STAGES, DemoOptions, DemoRunner


def create_parser() -> argparse.ArgumentParser:
    stage_choices = ["all"] + list(ALL_STAGES.keys())
    parser = argparse.ArgumentParser(
        description="SlayTheAmethyst agent demo — interactive debugging showcase."
    )
    parser.add_argument(
        "--stages",
        dest="stages",
        default="all",
        help=(
            "Comma-separated stage ids to run.  "
            "Use 'all' for the full demo.  "
            f"Available: {', '.join(stage_choices)}"
        ),
    )
    parser.add_argument(
        "-DeviceSerial",
        "--device-serial",
        dest="device_serial",
        default="",
        help="adb device serial.",
    )
    parser.add_argument(
        "-AgentPort",
        "--agent-port",
        dest="agent_port",
        type=int,
        default=9099,
        help="TCP port for game-probe (default 9099).",
    )
    parser.add_argument(
        "-OutDir",
        "--out-dir",
        dest="out_dir",
        default="",
        help="Output directory.  Defaults to demo-artifacts/<timestamp>.",
    )
    parser.add_argument(
        "--resume",
        dest="resume",
        action="store_true",
        help="Skip the setup stage — assume game is already running.",
    )
    parser.add_argument(
        "--no-cfr",
        dest="no_cfr",
        action="store_true",
        help="Skip CFR decompilation in the hotreload stage.",
    )
    parser.add_argument(
        "--install-test-crash",
        dest="install_test_crash",
        action="store_true",
        help=(
            "Push the bundled TestCrashCard.jar to the device and enable it. "
            "Run this BEFORE starting the game (before harness smoke). "
            "Exits after installation — no stages are executed."
        ),
    )
    return parser


def parse_args(argv: list[str] | None = None) -> DemoOptions:
    parser = create_parser()
    args = parser.parse_args(argv)
    stage_list = tuple(t.strip() for t in args.stages.split(",") if t.strip())
    return DemoOptions(
        device_serial=args.device_serial,
        agent_port=args.agent_port,
        out_dir=args.out_dir,
        stages=stage_list,
        resume=args.resume,
        no_cfr=args.no_cfr,
        install_test_crash=args.install_test_crash,
    )


def main(argv: list[str] | None = None) -> int:
    options = parse_args(argv)
    return DemoRunner(options).run()
