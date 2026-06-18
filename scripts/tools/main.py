from __future__ import annotations

import argparse
import sys

from lib import sts_harness_cli


def create_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="SlayTheAmethyst tool entrypoint.")
    subparsers = parser.add_subparsers(dest="tool")
    subparsers.add_parser("sts-harness", help="Run the Android debug harness.", add_help=False)
    subparsers.add_parser("harness", help="Alias for sts-harness.", add_help=False)
    return parser


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv or argv[0] in {"-h", "--help"}:
        create_parser().print_help()
        return 0 if argv else 2
    tool = argv.pop(0)
    if tool in {"sts-harness", "harness"}:
        return sts_harness_cli.main(argv)
    parser = create_parser()
    parser.error(f"unknown tool: {tool}")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
