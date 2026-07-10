import json
import subprocess
from pathlib import Path

from scripts.tools.lib.agent_bridge import AgentBridge, AgentBridgeError
from scripts.tools.lib.agent_protocol import AgentProtocol
from scripts.tools.lib.harness_connection import HarnessConnection
from scripts.tools.harness._context import HarnessContext, set_result_success


def _connect_agent(ctx: HarnessContext) -> HarnessConnection:
    port = ctx.options.agent_port
    conn = HarnessConnection(adb_runner=_adb_runner_factory(ctx), port=port)
    conn.setup_forward()
    conn.connect()
    return conn


def _adb_runner_factory(ctx: HarnessContext):
    from scripts.tools.harness._runner import adb as _adb
    class _Runner:
        def __call__(self, *args, **kw):
            return _adb(ctx, *args, **kw)
    return _Runner()


def run_agent_attach(ctx: HarnessContext, resolved_out_dir: Path) -> None:
    port = ctx.options.agent_port
    spec = ctx.options.agent_spec
    if not spec:
        set_result_success(ctx, False, "ERROR", "Agent spec is required for agent-attach.")
        return
    conn = _connect_agent(ctx)
    bridge = AgentBridge(port=port, connection=conn)
    try:
        agent_id = bridge.attach(spec)
        output_path = resolved_out_dir / f"agent_{agent_id}.jsonl"
        ctx.result.setdefault("artifacts", {})["agentData"] = str(output_path)
        duration = ctx.options.agent_duration or 30.0
        event_count = bridge.subscribe_and_capture(agent_id, output_path, timeout_seconds=duration)
        info = bridge.status(agent_id)
        ctx.result["agentInfo"] = {
            "agentId": agent_id, "spec": spec, "port": port,
            "state": info["state"], "eventCount": event_count,
            "outputFile": str(output_path),
        }
        set_result_success(ctx, True, "AGENT_ATTACHED", f"Attached {agent_id}, captured {event_count} events.")
    except AgentBridgeError as exc:
        set_result_success(ctx, False, "ERROR", str(exc))
    except Exception as exc:
        set_result_success(ctx, False, "ERROR", f"Agent bridge error: {exc}")
    finally:
        conn.close()
        conn.remove_forward()


def run_agent_detach(ctx: HarnessContext, resolved_out_dir: Path) -> None:
    spec = ctx.options.agent_spec or ""
    if not spec:
        set_result_success(ctx, False, "ERROR", "Agent spec prefix is required for agent-detach.")
        return
    conn = _connect_agent(ctx)
    bridge = AgentBridge(port=ctx.options.agent_port, connection=conn)
    try:
        agent_id = spec.split("@")[0]
        bridge.detach(agent_id)
        set_result_success(ctx, True, "AGENT_DETACHED", f"Detached {agent_id}.")
    except AgentBridgeError as exc:
        set_result_success(ctx, False, "ERROR", str(exc))
    except Exception as exc:
        set_result_success(ctx, False, "ERROR", f"Agent bridge error: {exc}")
    finally:
        conn.close()
        conn.remove_forward()


def run_agent_list(ctx: HarnessContext, resolved_out_dir: Path) -> None:
    conn = _connect_agent(ctx)
    bridge = AgentBridge(port=ctx.options.agent_port, connection=conn)
    try:
        agents = bridge.list_agents()
        ctx.result["agentList"] = agents
        set_result_success(ctx, True, "AGENTS_LISTED", f"Found {len(agents)} attached agent(s).")
    except AgentBridgeError as exc:
        set_result_success(ctx, False, "ERROR", str(exc))
    except Exception as exc:
        set_result_success(ctx, False, "ERROR", f"Agent bridge error: {exc}")
    finally:
        conn.close()
        conn.remove_forward()


def run_agent_status(ctx: HarnessContext, resolved_out_dir: Path) -> None:
    spec = ctx.options.agent_spec or ""
    if not spec:
        set_result_success(ctx, False, "ERROR", "Agent spec prefix is required for agent-status.")
        return
    conn = _connect_agent(ctx)
    bridge = AgentBridge(port=ctx.options.agent_port, connection=conn)
    try:
        agent_id = spec.split("@")[0]
        info = bridge.status(agent_id)
        ctx.result["agentInfo"] = info
        set_result_success(ctx, True, "AGENT_STATUS", f"{agent_id}: {info['state']}, {info['event_count']} events.")
    except AgentBridgeError as exc:
        set_result_success(ctx, False, "ERROR", str(exc))
    except Exception as exc:
        set_result_success(ctx, False, "ERROR", f"Agent bridge error: {exc}")
    finally:
        conn.close()
        conn.remove_forward()
