from __future__ import annotations

import sys

from scripts.tools.connector.client import ConnectorClient, Stream
from scripts.tools.lib.agent_client import AgentClient
from scripts.tools.arthas.cli import run_query, run_shell
from scripts.tools.arthas.manager import ArthasManager


def main() -> int:
    if len(sys.argv) < 2:
        _usage()
        return 1

    cmd = sys.argv[1]
    if cmd == "start":
        return _cmd_start()
    elif cmd == "shell":
        return _cmd_shell()
    elif cmd == "query":
        return _cmd_query()
    elif cmd == "stop":
        return _cmd_stop()
    else:
        _usage()
        return 1


def _usage() -> None:
    print("Usage: python -m scripts.tools.arthas <start|shell|query|stop>")
    print("  start      – push JARs, load agent, forward ports")
    print("  shell      – interactive Arthas shell via connect_stream(:8099)")
    print("  query CMD  – one-shot Arthas command")
    print("  stop       – unforward ports")


def _make_arthas_stream(port: int = 8099) -> Stream:
    c = ConnectorClient()
    c.connect()
    c.select("auto")
    c.forward(port=port)
    return c.connect_stream(port=port)


def _cmd_start() -> int:
    conn = ConnectorClient()
    conn.connect()
    conn.select("auto")
    conn.forward(port=9099)
    agent = AgentClient(connector=conn, port=9099)
    agent.connect()
    mgr = ArthasManager(connector=conn, agent_client=agent)
    mgr.start()
    print("Arthas started.  Use 'shell' or 'query' to interact.")
    return 0


def _cmd_shell() -> int:
    conn = ConnectorClient()
    conn.connect()
    conn.select("auto")
    conn.forward(port=8099)
    stream = conn.connect_stream(port=8099)
    run_shell(stream, reconnect_fn=_make_arthas_stream)
    stream.close()
    conn.unforward(port=8099)
    return 0


def _cmd_query() -> int:
    if len(sys.argv) < 3:
        print("Usage: python -m scripts.tools.arthas query <command>")
        return 1
    conn = ConnectorClient()
    conn.connect()
    conn.select("auto")
    conn.forward(port=8099)
    stream = conn.connect_stream(port=8099)
    run_query(stream, " ".join(sys.argv[2:]), reconnect_fn=_make_arthas_stream)
    stream.close()
    conn.unforward(port=8099)
    return 0


def _cmd_stop() -> int:
    conn = ConnectorClient()
    conn.connect()
    mgr = ArthasManager(connector=conn, agent_client=None)
    mgr.stop()
    print("Arthas stopped.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
