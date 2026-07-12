import json
from pathlib import Path

from scripts.tools.lib.agent_bridge import AgentBridge, AgentBridgeError
from scripts.tools.lib.agent_protocol import AgentProtocol
from scripts.tools.harness._context import HarnessContext, set_result_success
from scripts.tools.harness.agent import _connect_agent


def run_play(ctx: HarnessContext, resolved_out_dir: Path) -> None:
    conn = _connect_agent(ctx)
    bridge = AgentBridge(port=ctx.options.agent_port, connection=conn)
    proto = AgentProtocol(conn)
    try:
        agent_id = bridge.attach("play")
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
                state = proto.observe()
                print(json.dumps(state, indent=2))
            elif line.startswith("console "):
                cmd = line[len("console "):].strip()
                result = proto.console_exec(cmd)
                print(json.dumps(result, indent=2))
            elif line.startswith("play_card"):
                proto.execute("PLAY_CARD", {})
                state = proto.observe()
                print(json.dumps(state, indent=2))
            elif line == "end_turn":
                proto.execute("END_TURN", {})
                state = proto.observe()
                print(json.dumps(state, indent=2))
            elif line == "skip_room":
                proto.execute("SKIP_ROOM", {})
                state = proto.observe()
                print(json.dumps(state, indent=2))
            elif line == "press_proceed":
                proto.execute("PRESS_PROCEED", {})
            elif line.startswith("wait"):
                parts = line.split()
                ms = int(parts[1]) if len(parts) > 1 else 500
                proto.execute("WAIT", {"ms": ms})
            else:
                print(f"Unknown: {line}")
                print("Available: observe, play_card, end_turn, skip_room, press_proceed, wait <ms>, exit")
        bridge.detach(agent_id)
        set_result_success(ctx, True, "AGENT_PLAY_COMPLETE", "Interactive play session finished.")
    except AgentBridgeError as exc:
        set_result_success(ctx, False, "ERROR", str(exc))
    except Exception as exc:
        set_result_success(ctx, False, "ERROR", f"Play mode error: {exc}")
    finally:
        conn.close()
        conn.remove_forward()
