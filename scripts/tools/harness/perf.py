from pathlib import Path

from scripts.tools.lib.agent_client import AgentError
from scripts.tools.harness._context import HarnessContext, set_result_success
from scripts.tools.harness.agent import _connect_agent


def run_perf(ctx: HarnessContext, resolved_out_dir: Path) -> None:
    client = _connect_agent(ctx)
    try:
        spec = ctx.options.agent_spec.strip()
        if not spec:
            set_result_success(ctx, False, "ERROR", "Specify -AgentSpec for perf test.")
            return
        agent_id = client.attach(spec)
        client.perf_start(agent_id)
        duration = ctx.options.agent_duration or 10.0
        out_path = resolved_out_dir / f"agent_{agent_id}.jsonl"
        event_count = client.subscribe_and_capture(agent_id, out_path, duration)
        perf_info = client.perf_stop(agent_id)
        status_info = client.status(agent_id)
        ctx.result["agentInfo"] = {**status_info, "perf": perf_info}
        ctx.result.setdefault("artifacts", {})["agentData"] = str(out_path)
        client.detach(agent_id)
        set_result_success(ctx, True, "PERF_COMPLETE", f"Perf complete: {event_count} events, stats={perf_info}")
    except AgentError as exc:
        set_result_success(ctx, False, "ERROR", str(exc))
    except Exception as exc:
        set_result_success(ctx, False, "ERROR", f"Perf error: {exc}")
    finally:
        client.close()
