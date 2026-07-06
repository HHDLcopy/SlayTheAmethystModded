from __future__ import annotations

import sys
from typing import Any

from scripts.tools.connector.client import Stream
from scripts.tools.arthas.shell import ArthasShell


def run_query(
    stream: Stream,
    command: str,
    stdout: Any = sys.stdout,
) -> None:
    shell = ArthasShell(stream=stream)
    result = shell.command(command)
    stdout.write(result + "\n")


def run_shell(
    stream: Stream,
    stdin: Any = sys.stdin,
    stdout: Any = sys.stdout,
) -> None:
    shell = ArthasShell(stream=stream)
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
