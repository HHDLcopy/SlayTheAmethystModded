import subprocess
from pathlib import Path

from scripts.tools.lib.agent_bridge import AgentBridgeError
from scripts.tools.lib.agent_protocol import AgentProtocol
from scripts.tools.harness._context import HarnessContext, set_result_success
from scripts.tools.harness.agent import _connect_agent


def run_hotreload(ctx: HarnessContext, resolved_out_dir: Path) -> None:
    conn = _connect_agent(ctx)
    proto = AgentProtocol(conn)
    try:
        redefine_file = ctx.options.redefine_class_file.strip()
        if redefine_file:
            class_path = Path(redefine_file)
            if not class_path.is_file():
                set_result_success(ctx, False, "ERROR", f"Class file not found: {redefine_file}")
                return
            data = class_path.read_bytes()
            proto.redefine_class(data)
            set_result_success(ctx, True, "CLASS_REDEFINED", f"Redefined class from {redefine_file} ({len(data)} bytes)")
        else:
            target = (ctx.options.decompil_targets or [""])[0]
            if not target.strip():
                set_result_success(ctx, False, "ERROR", "Specify -Target <class name> to dump.")
                return
            class_name = target.strip()
            data = proto.dump_class(class_name)
            output = resolved_out_dir / f"{class_name.replace('.', '/')}.class"
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(data)
            ctx.result.setdefault("artifacts", {})["dumpedClass"] = str(output)
            decompiled = _decompil_class_bytes(ctx, data, class_name, resolved_out_dir)
            if decompiled:
                ctx.result["artifacts"]["decompiledSource"] = decompiled
            msg = f"Dumped {class_name} ({len(data)} bytes) to {output}"
            if decompiled:
                msg += f", decompiled to {decompiled}"
            set_result_success(ctx, True, "CLASS_DUMPED", msg)
    except AgentBridgeError as exc:
        set_result_success(ctx, False, "ERROR", str(exc))
    except Exception as exc:
        set_result_success(ctx, False, "ERROR", f"Hotreload error: {exc}")
    finally:
        conn.close()
        conn.remove_forward()


def _decompil_class_bytes(ctx: HarnessContext, data: bytes, class_name: str, out_dir: Path) -> str | None:
    cfr_jar = ctx.repo_root / "scripts" / "tools" / "lib" / "cfr.jar"
    if not cfr_jar.exists():
        return None
    class_file = out_dir / f"{class_name}.class"
    class_file.write_bytes(data)
    java_file = out_dir / f"{class_name.replace('.', '/')}.java"
    java_file.parent.mkdir(parents=True, exist_ok=True)
    try:
        result = subprocess.run(
            ["java", "-jar", str(cfr_jar), class_name, "--outputdir", str(out_dir),
             "--extraclasspath", str(class_file.parent)],
            text=True, encoding="utf-8", errors="replace",
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30,
            check=False,
        )
        expected = out_dir / f"{class_name.replace('.', '/')}.java"
        if expected.exists():
            return str(expected)
        if result.stdout.strip():
            java_file.write_text(result.stdout)
            return str(java_file)
        return None
    except Exception:
        return None
