from pathlib import Path

from scripts.tools.lib.sts_harness import file_timestamp
from scripts.tools.harness._context import HarnessContext, set_result_success
from scripts.tools.harness._runner import adb


def run_screenshot(ctx: HarnessContext, output_directory: Path) -> Path:
    output_directory.mkdir(parents=True, exist_ok=True)
    timestamp = file_timestamp()
    remote_path = f"/sdcard/sts_harness_{timestamp}.png"
    local_path = output_directory / f"sts-screen-{timestamp}.png"
    adb(ctx, ["shell", "screencap", "-p", remote_path])
    try:
        adb(ctx, ["pull", remote_path, str(local_path)], timeout_seconds=60)
    finally:
        adb(ctx, ["shell", "rm", remote_path], allow_failure=True)
    if not local_path.exists() or local_path.stat().st_size <= 0:
        raise RuntimeError(f"Screenshot was not created or is empty: {local_path}")
    ctx.result.setdefault("artifacts", {})["screenshot"] = str(local_path)
    set_result_success(ctx, True, "SCREENSHOT_CAPTURED", "Screenshot captured.")
    return local_path
