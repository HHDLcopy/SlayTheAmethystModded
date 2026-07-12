import json
from pathlib import Path

from scripts.tools.lib.agent_bridge import AgentBridge, AgentBridgeError
from scripts.tools.lib.agent_protocol import AgentProtocol
from scripts.tools.harness._context import HarnessContext, set_result_success
from scripts.tools.harness.agent import _connect_agent


def run_console(ctx: HarnessContext, resolved_out_dir: Path) -> None:
    command_text = getattr(ctx.options, "console_command", "").strip()

    conn = _connect_agent(ctx)
    proto = AgentProtocol(conn)
    try:
        if command_text:
            _execute_and_print(proto, command_text)
            set_result_success(ctx, True, "CONSOLE_EXECUTED", f"Console command executed: {command_text}")
        else:
            _interactive_repl(proto)
            set_result_success(ctx, True, "CONSOLE_SESSION_COMPLETE", "Interactive console session finished.")
    except AgentBridgeError as exc:
        set_result_success(ctx, False, "ERROR", str(exc))
    except Exception as exc:
        set_result_success(ctx, False, "ERROR", f"Console error: {exc}")
    finally:
        conn.close()
        conn.remove_forward()


def _execute_and_print(proto: AgentProtocol, command_text: str) -> None:
    print(f"console> {command_text}")
    result = proto.console_exec(command_text)
    if result.get("executed"):
        output = result.get("output", "")
        if output and output != "ok":
            print(output)
        else:
            print(f"  executed: {command_text}")
    else:
        error = result.get("error", "unknown error")
        print(f"  error: {error}")


def _interactive_repl(proto: AgentProtocol) -> None:
    print(
        "\n=== BaseMod Console Mode ===\n"
        "Type BaseMod console commands (e.g. gold 999, unlock Ironclad).\n"
        "Commands: help, exit | quit | q\n"
    )
    while True:
        try:
            line = input("console> ").strip()
        except (EOFError, KeyboardInterrupt):
            break
        if not line:
            continue
        if line in ("exit", "quit", "q"):
            break
        _execute_and_print(proto, line)
