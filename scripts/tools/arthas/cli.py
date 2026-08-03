from __future__ import annotations

import sys
from typing import Any, Callable

from scripts.tools.connector.client import Stream
from scripts.tools.arthas.shell import ArthasShell


def _normalize_query(command: str) -> str:
    """Make dashboard queries finite so the one-shot CLI can finish reliably."""
    normalized = command.strip()
    if normalized == "dashboard":
        return "dashboard -n 1"
    return command


def run_query(
    stream: Stream,
    command: str,
    reconnect_fn: Callable[[], Stream] | None = None,
    duration: float | None = None,
    stdout: Any = sys.stdout,
) -> ArthasShell:
    shell = ArthasShell(stream=stream, reconnect_fn=reconnect_fn)
    normalized = _normalize_query(command)
    if duration is None:
        result = shell.command(normalized)
    else:
        result = shell.command(normalized, duration=duration)
    stdout.write(result + "\n")
    return shell


def run_shell(
    stream: Stream,
    reconnect_fn: Callable[[], Stream] | None = None,
    stdin: Any = sys.stdin,
    stdout: Any = sys.stdout,
) -> ArthasShell:
    shell = ArthasShell(stream=stream, reconnect_fn=reconnect_fn)
    while True:
        try:
            stdout.write("arthas> ")
            stdout.flush()
            line = stdin.readline()
            if not line:
                break
            line = line.strip()
            if line in ("exit", "quit", "q"):
                break
            result = shell.command(line)
            stdout.write(result + "\n")
        except KeyboardInterrupt:
            stdout.write("\n")
            break
        except EOFError:
            break
    return shell
