from typing import Any
import re
from pathlib import Path

from scripts.tools.lib.sts_harness import (
    file_timestamp,
    quote_android_shell,
)
from scripts.tools.harness._context import HarnessContext
from scripts.tools.harness._runner import CommandResult, adb, adb_shell_script


def resolve_device_sts_root(ctx: HarnessContext) -> dict[str, Any]:
    package_name = ctx.application_id or ""
    candidates = [
        f"/sdcard/Android/data/{package_name}/files/sts",
        f"/storage/emulated/0/Android/data/{package_name}/files/sts",
    ]
    for candidate in candidates:
        probe = adb_shell_script(ctx, f"ls {quote_android_shell(candidate)} >/dev/null 2>&1", allow_failure=True)
        if probe.exit_code == 0:
            return {"root": candidate, "accessMode": "shell"}
    run_as = adb(ctx, ["exec-out", "run-as", package_name, "sh", "-c", "ls 'files/sts' >/dev/null 2>&1"], timeout_seconds=5, allow_failure=True)
    if run_as.exit_code == 0:
        return {"root": "files/sts", "accessMode": "run-as"}
    return {"root": candidates[0], "accessMode": "shell"}


def read_remote_sts_text(
    ctx: HarnessContext,
    sts_root: dict[str, Any],
    relative_path: str,
    tail_lines: int = 0,
    *,
    timeout_seconds: int = 5,
) -> str:
    trimmed = relative_path.lstrip("/")
    root_path = str(sts_root["root"])
    remote_path = root_path if not trimmed else f"{root_path}/{trimmed}"
    quoted = quote_android_shell(remote_path)
    if tail_lines > 0:
        script = f"if [ -f {quoted} ]; then tail -n {tail_lines} {quoted}; fi"
    else:
        script = f"if [ -f {quoted} ]; then cat {quoted}; fi"
    if sts_root["accessMode"] == "run-as":
        return adb(ctx, ["exec-out", "run-as", ctx.application_id or "", "sh", "-c", script], timeout_seconds=timeout_seconds, allow_failure=True).output
    return adb_shell_script(ctx, script, timeout_seconds=timeout_seconds, allow_failure=True).output


def parse_remote_path_state_output(relative_path: str, text: str | None) -> dict[str, Any]:
    exists = False
    item_type: str | None = None
    bytes_value: int | None = None
    mtime_epoch_seconds: int | None = None
    child_count: int | None = None
    jar_count: int | None = None
    for line in (text or "").splitlines():
        trimmed_line = line.strip()
        if trimmed_line == "exists=1":
            exists = True
        elif trimmed_line.startswith("type="):
            item_type = trimmed_line[len("type="):]
        elif trimmed_line.startswith("bytes="):
            try:
                bytes_value = int(trimmed_line[len("bytes="):])
            except ValueError:
                pass
        elif trimmed_line.startswith("mtimeEpochSeconds="):
            try:
                mtime_epoch_seconds = int(trimmed_line[len("mtimeEpochSeconds="):])
            except ValueError:
                pass
        elif trimmed_line.startswith("childCount="):
            try:
                child_count = int(trimmed_line[len("childCount="):])
            except ValueError:
                pass
        elif trimmed_line.startswith("jarCount="):
            try:
                jar_count = int(trimmed_line[len("jarCount="):])
            except ValueError:
                pass
    return {
        "relativePath": relative_path,
        "exists": exists,
        "type": item_type,
        "bytes": bytes_value,
        "mtimeEpochSeconds": mtime_epoch_seconds,
        "childCount": child_count,
        "jarCount": jar_count,
    }


def clear_runtime_signals(ctx: HarnessContext) -> None:
    sts_root = resolve_device_sts_root(ctx)
    for relative_path in ("boot_bridge_events.log", "latest.log"):
        remote_path = f"{sts_root['root']}/{relative_path}"
        quoted = quote_android_shell(remote_path)
        if sts_root["accessMode"] == "run-as":
            adb(ctx, ["exec-out", "run-as", ctx.application_id or "", "sh", "-c", f"rm -f {quoted}"], allow_failure=True)
        else:
            adb_shell_script(ctx, f"rm -f {quoted}", allow_failure=True)


def remote_sts_root_script(
    ctx: HarnessContext,
    sts_root: dict[str, Any],
    script: str,
    *,
    timeout_seconds: int = 5,
    allow_failure: bool = True,
) -> CommandResult:
    if sts_root["accessMode"] == "run-as":
        return adb(
            ctx,
            ["exec-out", "run-as", ctx.application_id or "", "sh", "-c", script],
            timeout_seconds=timeout_seconds,
            allow_failure=allow_failure,
        )
    return adb_shell_script(ctx, script, timeout_seconds=timeout_seconds, allow_failure=allow_failure)


def device_logcat_timestamp(ctx: HarnessContext) -> str:
    result = adb_shell_script(ctx, "date '+%m-%d %H:%M:%S.000' 2>/dev/null", timeout_seconds=5, allow_failure=True)
    if result.exit_code != 0:
        return ""
    for line in result.output.strip().splitlines():
        trimmed = line.strip()
        if re.match(r"^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}$", trimmed):
            return trimmed
    return ""


def remote_sts_path_state(ctx: HarnessContext, sts_root: dict[str, Any], relative_path: str) -> dict[str, Any]:
    trimmed = relative_path.lstrip("/")
    root_path = str(sts_root["root"])
    remote_path = root_path if not trimmed else f"{root_path}/{trimmed}"
    quoted = quote_android_shell(remote_path)
    state_script = f"""if [ -e {quoted} ]; then
  echo exists=1
  if [ -f {quoted} ]; then
    echo type=file
    size=$(wc -c < {quoted} 2>/dev/null | tr -d '[:space:]')
    echo bytes=$size
  elif [ -d {quoted} ]; then
    echo type=directory
    echo bytes=0
  else
    echo type=other
    echo bytes=0
  fi
  mtime=$(stat -c %Y {quoted} 2>/dev/null || echo '')
  echo mtimeEpochSeconds=$mtime
else
  echo exists=0
fi
"""
    result = remote_sts_root_script(ctx, sts_root, state_script)
    exists = False
    item_type: str | None = None
    bytes_value: int | None = None
    mtime_epoch_seconds: int | None = None
    for line in result.output.splitlines():
        trimmed_line = line.strip()
        if trimmed_line == "exists=1":
            exists = True
        elif trimmed_line.startswith("type="):
            item_type = trimmed_line[len("type="):]
        elif trimmed_line.startswith("bytes="):
            try:
                bytes_value = int(trimmed_line[len("bytes="):])
            except ValueError:
                pass
        elif trimmed_line.startswith("mtimeEpochSeconds="):
            try:
                mtime_epoch_seconds = int(trimmed_line[len("mtimeEpochSeconds="):])
            except ValueError:
                pass
    return {
        "relativePath": relative_path,
        "exists": exists,
        "type": item_type,
        "bytes": bytes_value,
        "mtimeEpochSeconds": mtime_epoch_seconds,
    }


def harness_logcat_dump(ctx: HarnessContext, output_directory: Path, since_timestamp: str = "") -> Path:
    if not ctx.adb_path:
        raise RuntimeError("adb is not initialized.")
    output_directory.mkdir(parents=True, exist_ok=True)
    log_path = output_directory / f"harness-logcat-dump-{file_timestamp()}.txt"
    logcat_args = ["logcat", "-d", "-v", "threadtime", "-b", "main", "-b", "system", "-b", "crash"]
    if since_timestamp.strip():
        logcat_args.extend(["-T", since_timestamp.strip()])
    result = adb(ctx, logcat_args, timeout_seconds=15, allow_failure=True)
    log_path.write_text(result.output, encoding="utf-8", errors="replace")
    ctx.result["artifacts"]["harnessLogcatDump"] = str(log_path)
    return log_path
