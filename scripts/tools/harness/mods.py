from typing import Any

from scripts.tools.lib import device_mods
from scripts.tools.harness._context import HarnessContext, set_result_success
from scripts.tools.harness._device import read_remote_sts_text, remote_sts_root_script, resolve_device_sts_root


class _DeviceModsAdapter:
    def __init__(self, ctx: HarnessContext) -> None:
        self.application_id: str | None = ctx.application_id
        self._ctx = ctx

    def resolve_device_sts_root(self) -> dict[str, Any]:
        return resolve_device_sts_root(self._ctx)

    def read_remote_sts_text(
        self, sts_root: dict[str, Any], relative_path: str,
        tail_lines: int = 0, *, timeout_seconds: int = 5,
    ) -> str:
        return read_remote_sts_text(self._ctx, sts_root, relative_path, tail_lines, timeout_seconds=timeout_seconds)

    def remote_sts_root_script(
        self, sts_root: dict[str, Any], script: str,
        *, timeout_seconds: int = 5, allow_failure: bool = True,
    ) -> Any:
        return remote_sts_root_script(self._ctx, sts_root, script, timeout_seconds=timeout_seconds, allow_failure=allow_failure)


def run_mods(ctx: HarnessContext) -> None:
    adapter = _DeviceModsAdapter(ctx)
    mods = device_mods.build_device_mod_snapshot(adapter)
    ctx.result["deviceMods"] = mods
    counts = mods["counts"]
    set_result_success(
        ctx, True, "MODS_LISTED",
        f"Listed {counts['optionalInstalled']} optional mods; {counts['optionalEnabled']} enabled.",
    )


def run_set_mods(ctx: HarnessContext) -> None:
    adapter = _DeviceModsAdapter(ctx)
    before = device_mods.build_device_mod_snapshot(adapter)
    tokens = _requested_mod_tokens(ctx)
    selection = device_mods.resolve_requested_mod_selection(
        before,
        tokens,
        enable_all_mods=ctx.options.enable_all_mods,
        disable_all_mods=ctx.options.disable_all_mods,
    )
    device_mods.write_enabled_mod_selection(adapter, before, selection["selectedStoragePaths"])
    after = device_mods.build_device_mod_snapshot(adapter)
    ctx.result["modSelection"] = {
        "beforeCounts": before["counts"],
        "selection": selection,
    }
    ctx.result["deviceMods"] = after
    counts = after["counts"]
    set_result_success(
        ctx, True, "MODS_SELECTED",
        f"Selected {counts['optionalEnabled']} of {counts['optionalInstalled']} optional mods.",
    )


def _requested_mod_tokens(ctx: HarnessContext) -> list[str]:
    tokens: list[str] = []
    for raw in ctx.options.mods:
        tokens.extend(device_mods.split_mod_tokens(raw))
    if ctx.options.mod_list_file.strip():
        path = ctx.repo_root / ctx.options.mod_list_file
        if not path.is_file():
            raise RuntimeError(f"Mod list file not found: {path}")
        tokens.extend(device_mods.read_mod_list_file(path))
        ctx.result.setdefault("artifacts", {})["modListFile"] = str(path)
    return tokens
