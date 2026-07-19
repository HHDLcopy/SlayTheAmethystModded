from pathlib import Path

from scripts.tools.lib.agent_client import AgentClient, AgentError
from scripts.tools.harness._context import HarnessContext, set_result_success


def _connect_agent(ctx: HarnessContext) -> AgentClient:
    if ctx.connector is None:
        raise RuntimeError("Harness connector is not initialized.")
    port = ctx.options.agent_port
    client = AgentClient(connector=ctx.connector, port=port)
    client.connect()
    return client


def run_agent_attach(ctx: HarnessContext, resolved_out_dir: Path) -> None:
    port = ctx.options.agent_port
    spec = ctx.options.agent_spec
    if not spec:
        set_result_success(ctx, False, "ERROR", "Agent spec is required for agent-attach.")
        return
    client = _connect_agent(ctx)
    try:
        agent_id = client.attach(spec)
        output_path = resolved_out_dir / f"agent_{agent_id}.jsonl"
        ctx.result.setdefault("artifacts", {})["agentData"] = str(output_path)
        duration = ctx.options.agent_duration or 30.0
        event_count = client.subscribe_and_capture(agent_id, output_path, timeout_seconds=duration)
        info = client.status(agent_id)
        ctx.result["agentInfo"] = {
            "agentId": agent_id, "spec": spec, "port": port,
            "state": info["state"], "eventCount": event_count,
            "outputFile": str(output_path),
        }
        set_result_success(ctx, True, "AGENT_ATTACHED", f"Attached {agent_id}, captured {event_count} events.")
    except AgentError as exc:
        set_result_success(ctx, False, "ERROR", str(exc))
    except Exception as exc:
        set_result_success(ctx, False, "ERROR", f"Agent bridge error: {exc}")
    finally:
        client.close()


def run_agent_detach(ctx: HarnessContext, resolved_out_dir: Path) -> None:
    spec = ctx.options.agent_spec or ""
    if not spec:
        set_result_success(ctx, False, "ERROR", "Agent spec prefix is required for agent-detach.")
        return
    client = _connect_agent(ctx)
    try:
        agent_id = spec.split("@")[0]
        client.detach(agent_id)
        set_result_success(ctx, True, "AGENT_DETACHED", f"Detached {agent_id}.")
    except AgentError as exc:
        set_result_success(ctx, False, "ERROR", str(exc))
    except Exception as exc:
        set_result_success(ctx, False, "ERROR", f"Agent bridge error: {exc}")
    finally:
        client.close()


def run_agent_list(ctx: HarnessContext, resolved_out_dir: Path) -> None:
    client = _connect_agent(ctx)
    try:
        agents = client.list_agents()
        ctx.result["agentList"] = agents
        set_result_success(ctx, True, "AGENTS_LISTED", f"Found {len(agents)} attached agent(s).")
    except AgentError as exc:
        set_result_success(ctx, False, "ERROR", str(exc))
    except Exception as exc:
        set_result_success(ctx, False, "ERROR", f"Agent bridge error: {exc}")
    finally:
        client.close()


def run_agent_status(ctx: HarnessContext, resolved_out_dir: Path) -> None:
    spec = ctx.options.agent_spec or ""
    if not spec:
        set_result_success(ctx, False, "ERROR", "Agent spec prefix is required for agent-status.")
        return
    client = _connect_agent(ctx)
    try:
        agent_id = spec.split("@")[0]
        info = client.status(agent_id)
        ctx.result["agentInfo"] = info
        set_result_success(ctx, True, "AGENT_STATUS", f"{agent_id}: {info['state']}, {info['event_count']} events.")
    except AgentError as exc:
        set_result_success(ctx, False, "ERROR", str(exc))
    except Exception as exc:
        set_result_success(ctx, False, "ERROR", f"Agent bridge error: {exc}")
    finally:
        client.close()
