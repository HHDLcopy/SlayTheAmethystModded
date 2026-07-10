from scripts.tools.harness._context import HarnessContext, set_result_success
from scripts.tools.harness._status import harness_status


def run_doctor(ctx: HarnessContext) -> None:
    status = harness_status(ctx)
    ctx.result["statusSnapshot"] = status
    set_result_success(ctx, True, "OK", "Harness prerequisites are available.")
