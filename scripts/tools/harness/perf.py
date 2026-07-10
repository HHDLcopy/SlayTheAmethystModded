from pathlib import Path

from scripts.tools.lib.agent_bridge import AgentBridge, AgentBridgeError
from scripts.tools.lib.agent_protocol import AgentProtocol
from scripts.tools.harness._context import HarnessContext, set_result_success
from scripts.tools.harness.agent import _connect_agent


def run_perf(ctx: HarnessContext, resolved_out_dir: Path) -> None:
    conn = _connect_agent(ctx)
    bridge = AgentBridge(port=ctx.options.agent_port, connection=conn)
    proto = AgentProtocol(conn)
    try:
        spec = ctx.options.agent_spec.strip()
        if not spec:
            set_result_success(ctx, False, "ERROR", "Specify -AgentSpec for perf test.")
            return
        agent_id = bridge.attach(spec)
        proto.perf_start(agent_id)
        duration = ctx.options.agent_duration or 10.0
        out_path = resolved_out_dir / f"agent_{agent_id}.jsonl"
        event_count = bridge.subscribe_and_capture(agent_id, out_path, duration)
        perf_info = proto.perf_stop(agent_id)
        status_info = bridge.status(agent_id)
        ctx.result["agentInfo"] = {**status_info, "perf": perf_info}
        ctx.result.setdefault("artifacts", {})["agentData"] = str(out_path)
        bridge.detach(agent_id)
        set_result_success(ctx, True, "PERF_COMPLETE", f"Perf complete: {event_count} events, stats={perf_info}")
    except AgentBridgeError as exc:
        set_result_success(ctx, False, "ERROR", str(exc))
    except Exception as exc:
        set_result_success(ctx, False, "ERROR", f"Perf error: {exc}")
    finally:
        conn.close()
        conn.remove_forward()
