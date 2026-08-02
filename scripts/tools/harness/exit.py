from pathlib import Path
import time

from scripts.tools.harness._context import HarnessContext, set_result_success
from scripts.tools.harness._device import resolve_device_sts_root, remote_sts_root_script
from scripts.tools.harness._status import harness_status
from scripts.tools.harness.run import run_stop
from scripts.tools.lib.sts_harness import quote_android_shell


def run_exit(ctx: HarnessContext, resolved_out_dir: Path) -> None:
    sts_root = resolve_device_sts_root(ctx)
    request_path = f"{sts_root['root']}/.harness_exit_request"
    quoted = quote_android_shell(request_path)
    remote_sts_root_script(ctx, sts_root, f"mkdir -p $(dirname {quoted}) && printf 'exit\\n' > {quoted}", allow_failure=False)
    ctx.result.setdefault("artifacts", {})["exitRequest"] = request_path

    deadline = time.monotonic() + max(1, ctx.options.timeout_seconds)
    status = harness_status(ctx)
    while time.monotonic() < deadline:
        if not status["processes"]["game"].strip():
            ctx.result["statusSnapshot"] = status
            set_result_success(ctx, True, "EXITED", "Game exited through the GDX close request.")
            return
        time.sleep(max(0.25, ctx.options.poll_interval_seconds))
        status = harness_status(ctx)

    ctx.result["statusSnapshot"] = status
    try:
        run_stop(ctx)
        set_result_success(ctx, False, "EXIT_TIMEOUT", "GDX close request timed out; force-stop fallback was used.")
    except Exception as exc:
        set_result_success(ctx, False, "EXIT_TIMEOUT", f"GDX close request timed out and force-stop failed: {exc}")
