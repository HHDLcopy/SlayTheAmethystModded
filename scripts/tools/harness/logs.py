from pathlib import Path

from scripts.tools.lib.sts_harness import read_local_text_tail
from scripts.tools.harness._context import HarnessContext, set_result_success
from scripts.tools.harness._device import harness_logcat_dump
from scripts.tools.harness._runner import gradle
from scripts.tools.harness._status import harness_status


def _gradle_device_properties(ctx: HarnessContext) -> list[str]:
    device_serial = ctx.resolved_device_serial.strip()
    if not device_serial:
        return []
    return [f"-PandroidDeviceSerial={device_serial}"]


def run_logs(ctx: HarnessContext, output_directory: Path) -> None:
    output_directory.mkdir(parents=True, exist_ok=True)
    gradle(ctx, [":app:stsPullLogs", f"-PlogsDir={output_directory}", *_gradle_device_properties(ctx)])
    archives = sorted(output_directory.glob("sts-jvm-logs-export-*.zip"), key=lambda item: item.stat().st_mtime, reverse=True)
    if archives:
        ctx.result.setdefault("artifacts", {})["logsZip"] = str(archives[0])
    try:
        logcat_path = harness_logcat_dump(ctx, output_directory)
        logcat_text = read_local_text_tail(logcat_path, max_bytes=262144)
        ctx.result["statusSnapshot"] = harness_status(ctx, logcat_text, str(logcat_path))
    except Exception as exc:
        ctx.result.setdefault("artifacts", {})["harnessLogcatError"] = str(exc)
    set_result_success(ctx, True, "LOGS_EXPORTED", "Log export completed.")
