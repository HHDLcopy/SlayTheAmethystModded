import json
from pathlib import Path

from scripts.tools.lib.agent_client import AgentClient, AgentError
from scripts.tools.harness._context import HarnessContext, set_result_success
from scripts.tools.harness.agent import _connect_agent


def run_play(ctx: HarnessContext, resolved_out_dir: Path) -> None:
    client = _connect_agent(ctx)
    try:
        agent_id = client.attach("play")
        ctx.result["agentInfo"] = {"agentId": agent_id, "state": "active"}
        print(
            "\n=== Agent Play Mode ===\n"
            f"Agent: {agent_id}\n"
            "Commands: observe, play_card, end_turn, skip_room, exit\n"
            "Console: console <cmd> (e.g. console gold 999)\n"
        )
        while True:
            try:
                line = input("play> ").strip()
            except (EOFError, KeyboardInterrupt):
                break
            if not line:
                continue
            if line in ("exit", "quit", "q"):
                break
            if line == "observe":
                state = client.observe()
                print(json.dumps(state, indent=2))
            elif line.startswith("console "):
                cmd = line[len("console "):].strip()
                result = client.console_exec(cmd)
                print(json.dumps(result, indent=2))
            elif line.startswith("play_card"):
                client.execute("PLAY_CARD", {})
                state = client.observe()
                print(json.dumps(state, indent=2))
            elif line == "end_turn":
                client.execute("END_TURN", {})
                state = client.observe()
                print(json.dumps(state, indent=2))
            elif line == "skip_room":
                client.execute("SKIP_ROOM", {})
                state = client.observe()
                print(json.dumps(state, indent=2))
            elif line == "press_proceed":
                client.execute("PRESS_PROCEED", {})
            elif line.startswith("wait"):
                parts = line.split()
                ms = int(parts[1]) if len(parts) > 1 else 500
                client.execute("WAIT", {"ms": ms})
            else:
                print(f"Unknown: {line}")
                print("Available: observe, play_card, end_turn, skip_room, press_proceed, wait <ms>, exit")
        client.detach(agent_id)
        set_result_success(ctx, True, "AGENT_PLAY_COMPLETE", "Interactive play session finished.")
    except AgentError as exc:
        set_result_success(ctx, False, "ERROR", str(exc))
    except Exception as exc:
        set_result_success(ctx, False, "ERROR", f"Play mode error: {exc}")
    finally:
        client.close()
