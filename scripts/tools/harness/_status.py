import re
from typing import Any

from scripts.tools.lib.sts_harness import (
    SINGLE_ROOM_RESULT_PREFIX,
    limit_text,
    text_contains,
)


def parse_boot_bridge_events(text: str | None) -> dict[str, Any]:
    latest = None
    terminal = None
    count = 0
    for line in re.split(r"\r?\n", text or ""):
        trimmed = line.strip()
        if not trimmed:
            continue
        parts = trimmed.split("\t", 2)
        event_type = parts[0].strip().upper()
        progress = None
        if len(parts) >= 2:
            try:
                progress = int(parts[1].strip())
            except ValueError:
                pass
        message = parts[2].strip() if len(parts) >= 3 else ""
        event = {"type": event_type, "progress": progress, "message": message}
        latest = event
        count += 1
        if event_type in ("READY", "FAIL"):
            terminal = event
    return {"eventCount": count, "latestEvent": latest, "terminalEvent": terminal}


def find_crash_marker(text: str | None) -> str | None:
    for marker in (
        "Game crashed.",
        "Exception occurred in CardCrawlGame render method!",
        'Exception in thread "LWJGL Application"',
        "Forced runtime crash for expected-exit verification",
    ):
        if text_contains(text, marker):
            return marker
    return None


def find_single_room_result(text: str | None) -> dict[str, Any] | None:
    if not text or not text.strip():
        return None
    result_line = None
    for line in re.split(r"\r?\n", text):
        if SINGLE_ROOM_RESULT_PREFIX in line:
            result_line = line.strip()
    if result_line is None:
        return None
    payload = result_line.split(SINGLE_ROOM_RESULT_PREFIX, 1)[1].strip()
    values: dict[str, str] = {}
    for match in re.finditer(r"(\w+)=([^ ]+)", payload):
        values[match.group(1)] = match.group(2)
    return {
        "line": result_line,
        "outcome": values.get("outcome"),
        "character": values.get("character"),
        "monster": values.get("monster"),
        "turns": values.get("turns"),
        "playerHp": values.get("playerHp"),
        "monsterHp": values.get("monsterHp"),
        "detail": values.get("detail"),
    }


def find_harness_logcat_crash(text: str | None, package_name: str) -> dict[str, Any] | None:
    if not text or not text.strip():
        return None
    lines = re.split(r"\r?\n", text)
    markers = (
        "FATAL EXCEPTION",
        "Fatal signal",
        "AndroidRuntime",
        "Game crashed.",
        "Game body patch failed before launch",
        "Exception occurred in CardCrawlGame render method!",
        'Exception in thread "LWJGL Application"',
        "java.lang.OutOfMemoryError",
    )
    package_needles = (
        package_name,
        f"{package_name}:game",
        f"{package_name}:diag",
        f"Process: {package_name}",
        f">>> {package_name}",
    )
    for index, line in enumerate(lines):
        marker_matched = None
        for marker in markers:
            if text_contains(line, marker):
                marker_matched = marker
                break
        if marker_matched is None:
            for needle in package_needles:
                if text_contains(line, needle):
                    if (
                        text_contains(line, f"Process: {package_name}")
                        or text_contains(line, f">>> {package_name}")
                        or text_contains(line, f"Cmdline: {package_name}")
                        or text_contains(line, "Force finishing")
                    ):
                        marker_matched = needle
                    break
        if marker_matched is None:
            continue
        start = max(0, index - 12)
        end = min(len(lines) - 1, index + 90)
        window_text = "\n".join(lines[start : end + 1])
        package_matched = any(text_contains(window_text, needle) for needle in package_needles)
        runtime_log_marker = marker_matched in (
            "Game crashed.",
            "Game body patch failed before launch",
            "Exception occurred in CardCrawlGame render method!",
            'Exception in thread "LWJGL Application"',
        )
        if not package_matched and not runtime_log_marker:
            continue
        return {
            "marker": marker_matched,
            "line": line.strip(),
            "packageMatched": package_matched,
            "excerpt": limit_text(window_text, 5000),
        }
    return None


def last_non_blank_line(text: str | None) -> str | None:
    last = None
    for line in re.split(r"\r?\n", text or ""):
        trimmed = line.strip()
        if trimmed:
            last = trimmed
    return last
