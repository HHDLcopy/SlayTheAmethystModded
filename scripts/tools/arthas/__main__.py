from __future__ import annotations

import argparse
import sys
from typing import Callable

from scripts.tools.connector.client import ConnectorClient, Stream
from scripts.tools.lib.agent_client import AgentClient
from scripts.tools.lib.env_device import get_test_device_serial
from scripts.tools.arthas.cli import run_query, run_shell
from scripts.tools.arthas.manager import ArthasManager


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(list(sys.argv[1:] if argv is None else argv))
    if args.command == "start":
        return _cmd_start(args)
    if args.command == "shell":
        return _cmd_shell(args)
    if args.command == "query":
        return _cmd_query(args)
    if args.command == "stop":
        return _cmd_stop(args)
    _usage()
    return 1


def _parse_args(argv: list[str]) -> argparse.Namespace:
    duration = None
    normalized_argv = list(argv)
    if "--duration" in normalized_argv:
        index = normalized_argv.index("--duration")
        if index + 1 >= len(normalized_argv):
            raise SystemExit("--duration requires a number")
        try:
            duration = float(normalized_argv[index + 1])
        except ValueError:
            raise SystemExit("--duration requires a number") from None
        del normalized_argv[index:index + 2]
    parser = argparse.ArgumentParser(
        prog="python -m scripts.tools.arthas",
        description="Arthas lifecycle and query helper for SlayTheAmethyst Android JVMs.",
    )
    parser.add_argument(
        "--device",
        dest="device",
        default=None,
        help="ADB serial. Defaults to STS_TEST_DEVICE, then single online device.",
    )
    parser.add_argument(
        "--agent-port",
        dest="agent_port",
        type=int,
        default=9099,
        help="game-probe TCP port (default: 9099).",
    )
    parser.add_argument(
        "--arthas-port",
        dest="arthas_port",
        type=int,
        default=8099,
        help="Arthas bridge TCP port (default: 8099).",
    )
    parser.add_argument(
        "command",
        choices=("start", "shell", "query", "stop"),
        help="Lifecycle or query command.",
    )
    parser.add_argument(
        "query_parts",
        nargs="*",
        help="Arthas command text for query mode.",
    )
    parser.add_argument(
        "--duration",
        type=float,
        default=None,
        help="Seconds to collect monitor/watch/trace output before Ctrl-C.",
    )
    if not argv:
        parser.print_help()
        raise SystemExit(1)
    args = parser.parse_args(normalized_argv)
    args.duration = duration
    if args.command == "query" and not args.query_parts:
        parser.error("query requires an Arthas command")
    return args


def _usage() -> None:
    print("Usage: python -m scripts.tools.arthas [--device SERIAL] <start|shell|query|stop> [query cmd...]")
    print("  start      – push JARs, load agent, forward ports")
    print("  shell      – interactive Arthas shell via connect_stream(:8099)")
    print("  query CMD  – one-shot Arthas command")
    print("  stop       – reset/stop Arthas service and unforward ports")


def resolve_device(conn: ConnectorClient, cli_device: str | None) -> str:
    if cli_device and cli_device.strip() and cli_device.strip() != "auto":
        return cli_device.strip()

    env_device = get_test_device_serial()
    if env_device and env_device.strip() and env_device.strip() != "auto":
        return env_device.strip()

    devices = [
        d for d in (conn.devices() or [])
        if str(d.get("state", "")).lower() in ("device", "online")
    ]
    if len(devices) == 1:
        return devices[0]["serial"]
    if not devices:
        raise SystemExit("No online Android devices found for Arthas.")

    serials = ", ".join(d.get("serial", "?") for d in devices)
    raise SystemExit(
        "Multiple Android devices online; pass --device <serial> or set STS_TEST_DEVICE. "
        f"Available: {serials}"
    )


def _make_arthas_stream(device: str, port: int = 8099) -> Callable[[], Stream]:
    def _open() -> Stream:
        c = ConnectorClient()
        c.connect()
        if not c.select(device):
            raise RuntimeError(f"Failed to select device: {device}")
        c.forward(port=port)
        return c.connect_stream(port=port)

    return _open


def _cmd_start(args: argparse.Namespace) -> int:
    conn = ConnectorClient()
    agent = None
    try:
        conn.connect()
        device = resolve_device(conn, args.device)
        if not conn.select(device):
            raise RuntimeError(f"Failed to select device: {device}")
        conn.forward(port=args.agent_port)
        agent = AgentClient(connector=conn, port=args.agent_port)
        agent.connect()
        mgr = ArthasManager(connector=conn, agent_client=agent)
        mgr.start(port=args.arthas_port)
        print(f"Arthas started on {device}.  Use 'shell' or 'query' to interact.")
        return 0
    finally:
        if agent is not None:
            try:
                agent.close()
            except Exception:
                pass
        try:
            conn.unforward(port=args.agent_port)
        except Exception:
            pass
        try:
            conn.close()
        except Exception:
            pass


def _cmd_shell(args: argparse.Namespace) -> int:
    conn = ConnectorClient()
    stream = None
    shell = None
    try:
        conn.connect()
        device = resolve_device(conn, args.device)
        if not conn.select(device):
            raise RuntimeError(f"Failed to select device: {device}")
        conn.forward(port=args.arthas_port)
        stream = conn.connect_stream(port=args.arthas_port)
        shell = run_shell(
            stream,
            reconnect_fn=_make_arthas_stream(device, args.arthas_port),
        )
        return 0
    finally:
        if shell is not None:
            try:
                shell.close()
            except Exception:
                pass
        elif stream is not None:
            stream.close()
        try:
            conn.unforward(port=args.arthas_port)
        except Exception:
            pass
        try:
            conn.close()
        except Exception:
            pass


def _cmd_query(args: argparse.Namespace) -> int:
    conn = ConnectorClient()
    stream = None
    shell = None
    try:
        conn.connect()
        device = resolve_device(conn, args.device)
        if not conn.select(device):
            raise RuntimeError(f"Failed to select device: {device}")
        conn.forward(port=args.arthas_port)
        stream = conn.connect_stream(port=args.arthas_port)
        query_kwargs = {
            "reconnect_fn": _make_arthas_stream(device, args.arthas_port),
        }
        if args.duration is not None:
            query_kwargs["duration"] = args.duration
        shell = run_query(stream, " ".join(args.query_parts), **query_kwargs)
        return 0
    finally:
        if shell is not None:
            try:
                shell.close()
            except Exception:
                pass
        if stream is not None:
            try:
                stream.close()
            except Exception:
                pass
        try:
            conn.unforward(port=args.arthas_port)
        except Exception:
            pass
        try:
            conn.close()
        except Exception:
            pass


def _cmd_stop(args: argparse.Namespace) -> int:
    conn = ConnectorClient()
    try:
        conn.connect()
        device = resolve_device(conn, args.device)
        if not conn.select(device):
            raise RuntimeError(f"Failed to select device: {device}")
        mgr = ArthasManager(connector=conn, agent_client=None)
        mgr.stop(port=args.arthas_port)
        print(f"Arthas stopped on {device}.")
        return 0
    finally:
        try:
            conn.close()
        except Exception:
            pass


if __name__ == "__main__":
    raise SystemExit(main())
