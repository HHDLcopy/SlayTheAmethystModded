from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, Protocol


OPTIONAL_MOD_LIBRARY_RELATIVE = "mods_library"
LEGACY_MODS_RELATIVE = "mods"
ENABLED_MODS_CONFIG_RELATIVE = "enabled_mods.txt"
MTS_MOD_FILE_LIST_RELATIVE = ".mts_mod_file_list"
OPTIONAL_MOD_INDEX_RELATIVE = "optional_mod_index.json"

REQUIRED_MODS = {
    "BaseMod.jar": {"modId": "basemod", "name": "BaseMod"},
    "StSLib.jar": {"modId": "stslib", "name": "StSLib"},
    "AmethystRuntimeCompat.jar": {"modId": "amethystruntimecompat", "name": "Amethyst Runtime Compat"},
    "AmethystFloatingTools.jar": {"modId": "amethystfloatingtools", "name": "Amethyst Floating Tools"},
    "RamSaver.jar": {"modId": "ramsaver", "name": "Ram Saver"},
}

REQUIRED_MOD_IDS = {entry["modId"] for entry in REQUIRED_MODS.values()}
RESERVED_OPTIONAL_JAR_NAMES = {name.lower() for name in REQUIRED_MODS}


class HarnessLike(Protocol):
    application_id: str | None

    def resolve_device_sts_root(self) -> dict[str, Any]:
        ...

    def read_remote_sts_text(
        self,
        sts_root: dict[str, Any],
        relative_path: str,
        tail_lines: int = 0,
        *,
        timeout_seconds: int = 5,
    ) -> str:
        ...

    def remote_sts_root_script(
        self,
        sts_root: dict[str, Any],
        script: str,
        *,
        timeout_seconds: int = 5,
        allow_failure: bool = True,
    ) -> Any:
        ...


def quote_android_shell(value: str) -> str:
    return "'" + value.replace("'", "'\"'\"'") + "'"


def split_mod_tokens(raw: str | None) -> list[str]:
    if not raw:
        return []
    tokens = []
    for item in re.split(r"[\r\n,]+", raw):
        token = item.strip()
        if token:
            tokens.append(token)
    return tokens


def read_mod_list_file(path: Path) -> list[str]:
    tokens = []
    text = path.read_text(encoding="utf-8", errors="replace")
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        tokens.extend(split_mod_tokens(line))
    return tokens


def build_device_mod_snapshot(harness: HarnessLike) -> dict[str, Any]:
    sts_root = harness.resolve_device_sts_root()
    storage_root = storage_root_for_config(sts_root, harness.application_id or "")
    remote_rows = list_remote_mod_jars(harness, sts_root)
    enabled_tokens = parse_enabled_mod_tokens(
        harness.read_remote_sts_text(sts_root, ENABLED_MODS_CONFIG_RELATIVE, timeout_seconds=10)
    )
    metadata_by_relative_path = parse_optional_mod_index(
        harness.read_remote_sts_text(sts_root, OPTIONAL_MOD_INDEX_RELATIVE, timeout_seconds=10)
    )
    mts_mod_file_list = parse_enabled_mod_tokens(
        harness.read_remote_sts_text(sts_root, MTS_MOD_FILE_LIST_RELATIVE, timeout_seconds=10)
    )

    required_mods: list[dict[str, Any]] = []
    optional_mods: list[dict[str, Any]] = []
    legacy_runtime_mods: list[dict[str, Any]] = []

    for row in remote_rows:
        relative_path = remote_relative_path(row["path"])
        storage_path = normalize_remote_storage_path(row["path"], sts_root, harness.application_id or "")
        base = {
            "fileName": row["fileName"],
            "relativePath": relative_path,
            "storagePath": storage_path,
            "bytes": row["bytes"],
            "mtimeEpochSeconds": row["mtimeEpochSeconds"],
        }
        if row["kind"] == "required":
            info = REQUIRED_MODS.get(row["fileName"], {})
            required_mods.append(
                {
                    **base,
                    "modId": info.get("modId", ""),
                    "manifestModId": "",
                    "name": info.get("name", Path(row["fileName"]).stem),
                    "required": True,
                    "installed": True,
                    "enabled": True,
                }
            )
            continue

        metadata = metadata_by_relative_path.get(path_key(relative_path), {})
        entry_storage_path = metadata.get("storagePath") or storage_path_for_relative(storage_root, relative_path)
        entry = {
            **base,
            "storagePath": entry_storage_path,
            "modId": metadata.get("normalizedModId", ""),
            "manifestModId": metadata.get("rawModId", ""),
            "name": metadata.get("name") or Path(row["fileName"]).stem,
            "version": metadata.get("version", ""),
            "description": metadata.get("description", ""),
            "dependencies": metadata.get("dependencies", []),
            "launchModId": metadata.get("launchModId", ""),
            "launchValidationError": metadata.get("launchValidationError", ""),
            "required": False,
            "installed": True,
            "enabled": False,
        }
        if row["kind"] == "legacy":
            legacy_runtime_mods.append(entry)
        else:
            optional_mods.append(entry)

    selected_keys, unmatched_enabled_tokens = resolve_enabled_optional_mods(optional_mods, enabled_tokens)
    for mod in optional_mods:
        mod["enabled"] = entry_key(mod) in selected_keys
    optional_mods.sort(key=lambda item: (item["fileName"].lower(), item["fileName"], item["storagePath"]))
    legacy_runtime_mods.sort(key=lambda item: (item["fileName"].lower(), item["fileName"], item["storagePath"]))
    required_mods.sort(key=lambda item: (item["fileName"].lower(), item["fileName"], item["storagePath"]))

    enabled_optional_mods = [mod for mod in optional_mods if mod["enabled"]]
    return {
        "storage": {
            "root": sts_root.get("root"),
            "accessMode": sts_root.get("accessMode"),
            "configStorageRoot": storage_root,
        },
        "enabledModsConfig": {
            "relativePath": ENABLED_MODS_CONFIG_RELATIVE,
            "rawTokens": enabled_tokens,
            "unmatchedTokens": unmatched_enabled_tokens,
        },
        "requiredMods": required_mods,
        "optionalMods": optional_mods,
        "legacyRuntimeMods": legacy_runtime_mods,
        "enabledOptionalMods": enabled_optional_mods,
        "enabledOptionalStoragePaths": [mod["storagePath"] for mod in enabled_optional_mods],
        "mtsModFileList": mts_mod_file_list,
        "counts": {
            "requiredInstalled": len(required_mods),
            "optionalInstalled": len(optional_mods),
            "optionalEnabled": len(enabled_optional_mods),
            "legacyRuntimeInstalled": len(legacy_runtime_mods),
        },
    }


def resolve_requested_mod_selection(
    snapshot: dict[str, Any],
    requested_tokens: list[str],
    *,
    enable_all_mods: bool,
    disable_all_mods: bool,
) -> dict[str, Any]:
    modes = sum(1 for value in (bool(requested_tokens), enable_all_mods, disable_all_mods) if value)
    if modes == 0:
        raise ValueError("Pass -Mods, -ModListFile, -EnableAllMods, or -DisableAllMods with -Command set-mods.")
    if modes > 1:
        raise ValueError("Use only one set-mods selection mode: explicit mods, -EnableAllMods, or -DisableAllMods.")

    optional_mods = list(snapshot.get("optionalMods") or [])
    if enable_all_mods:
        selected_mods = optional_mods
        mode = "all"
    elif disable_all_mods:
        selected_mods = []
        mode = "none"
    else:
        selected_mods = match_requested_mod_tokens(optional_mods, requested_tokens)
        mode = "explicit"

    selected_paths = []
    seen = set()
    for mod in selected_mods:
        key = entry_key(mod)
        if key in seen:
            continue
        seen.add(key)
        selected_paths.append(mod["storagePath"])

    return {
        "mode": mode,
        "requestedTokens": requested_tokens,
        "selectedCount": len(selected_paths),
        "selectedStoragePaths": selected_paths,
        "selectedMods": [
            {
                "fileName": mod.get("fileName", ""),
                "modId": mod.get("modId", ""),
                "manifestModId": mod.get("manifestModId", ""),
                "name": mod.get("name", ""),
                "launchModId": mod.get("launchModId", ""),
                "storagePath": mod.get("storagePath", ""),
            }
            for mod in selected_mods
            if entry_key(mod) in seen
        ],
    }


def write_enabled_mod_selection(
    harness: HarnessLike,
    snapshot: dict[str, Any],
    selected_storage_paths: list[str],
) -> None:
    storage = snapshot.get("storage") or {}
    sts_root = {
        "root": storage.get("root") or "",
        "accessMode": storage.get("accessMode") or "shell",
    }
    root = str(sts_root["root"])
    root_quoted = quote_android_shell(root)
    if selected_storage_paths:
        writer_lines = "\n".join(
            f"  printf '%s\\n' {quote_android_shell(path)}" for path in selected_storage_paths
        )
        write_block = f"(\n{writer_lines}\n) > \"$tmp\""
    else:
        write_block = ': > "$tmp"'
    script = f"""root={root_quoted}
config="$root/{ENABLED_MODS_CONFIG_RELATIVE}"
tmp="$config.tmp.$$"
mkdir -p "$root" || exit 1
if {write_block}; then
  mv "$tmp" "$config" || exit 1
  rm -f "$root/{MTS_MOD_FILE_LIST_RELATIVE}"
else
  rm -f "$tmp"
  exit 1
fi
"""
    result = harness.remote_sts_root_script(sts_root, script, timeout_seconds=15, allow_failure=True)
    if result.exit_code != 0:
        raise RuntimeError(f"Failed to write {ENABLED_MODS_CONFIG_RELATIVE}: {result.output.strip()}")


def list_remote_mod_jars(harness: HarnessLike, sts_root: dict[str, Any]) -> list[dict[str, Any]]:
    root = quote_android_shell(str(sts_root["root"]))
    script = f"""root={root}
list_dir() {{
  kind="$1"
  dir="$2"
  if [ ! -d "$dir" ]; then
    return
  fi
  for path in "$dir"/*.jar; do
    [ -f "$path" ] || continue
    name="${{path##*/}}"
    lower="$(printf '%s' "$name" | tr '[:upper:]' '[:lower:]')"
    if [ "$kind" != "required" ]; then
      case "$lower" in
        basemod.jar|stslib.jar|amethystruntimecompat.jar|amethystfloatingtools.jar|ramsaver.jar)
          continue
          ;;
      esac
    fi
    bytes="$(wc -c < "$path" 2>/dev/null | tr -d '[:space:]')"
    mtime="$(stat -c %Y "$path" 2>/dev/null || echo '')"
    printf '%s\\t%s\\t%s\\t%s\\t%s\\n' "$kind" "$name" "$path" "$bytes" "$mtime"
  done
}}
list_dir required "$root/required_mods"
list_dir optional "$root/{OPTIONAL_MOD_LIBRARY_RELATIVE}"
list_dir legacy "$root/{LEGACY_MODS_RELATIVE}"
"""
    result = harness.remote_sts_root_script(sts_root, script, timeout_seconds=15, allow_failure=True)
    rows = []
    for line in result.output.splitlines():
        parts = line.split("\t")
        if len(parts) != 5:
            continue
        kind, file_name, path, bytes_text, mtime_text = parts
        if kind not in {"required", "optional", "legacy"}:
            continue
        rows.append(
            {
                "kind": kind,
                "fileName": file_name,
                "path": path,
                "bytes": parse_int(bytes_text),
                "mtimeEpochSeconds": parse_int(mtime_text),
            }
        )
    return rows


def parse_optional_mod_index(text: str | None) -> dict[str, dict[str, Any]]:
    if not text or not text.strip():
        return {}
    try:
        root = json.loads(text)
    except json.JSONDecodeError:
        return {}
    entries = root.get("entries")
    if not isinstance(entries, list):
        return {}
    result: dict[str, dict[str, Any]] = {}
    for item in entries:
        if not isinstance(item, dict) or not item.get("displayable", False):
            continue
        storage_path = str(item.get("storagePath") or "").strip()
        if not storage_path:
            continue
        relative = remote_relative_path(storage_path)
        dependencies = item.get("dependencies")
        if not isinstance(dependencies, list):
            dependencies = []
        result[path_key(relative)] = {
            "storagePath": storage_path,
            "rawModId": str(item.get("rawModId") or "").strip(),
            "normalizedModId": normalize_mod_id(str(item.get("normalizedModId") or "")),
            "name": str(item.get("name") or "").strip(),
            "version": str(item.get("version") or "").strip(),
            "description": str(item.get("description") or "").strip(),
            "dependencies": [str(value).strip() for value in dependencies if str(value).strip()],
            "launchModId": str(item.get("launchModId") or "").strip(),
            "launchValidationError": str(item.get("launchValidationError") or "").strip(),
        }
    return result


def parse_enabled_mod_tokens(text: str | None) -> list[str]:
    tokens = []
    for line in (text or "").splitlines():
        token = line.strip()
        if token:
            tokens.append(token)
    return tokens


def resolve_enabled_optional_mods(optional_mods: list[dict[str, Any]], raw_tokens: list[str]) -> tuple[set[str], list[str]]:
    selected: list[dict[str, Any]] = []
    unmatched: list[str] = []
    path_map = build_path_match_map(optional_mods)
    mod_id_map = build_mod_id_match_map(optional_mods)

    for raw in raw_tokens:
        token = raw.strip()
        if not token or not looks_like_path_token(token):
            continue
        match = path_map.get(path_key(token))
        if match is None:
            unmatched.append(raw)
            continue
        if entry_key(match) not in {entry_key(item) for item in selected}:
            selected.append(match)

    for raw in raw_tokens:
        token = raw.strip()
        if not token or looks_like_path_token(token):
            continue
        normalized = normalize_mod_id(token)
        if not normalized or normalized in REQUIRED_MOD_IDS:
            continue
        candidates = mod_id_map.get(normalized, [])
        if not candidates:
            unmatched.append(raw)
            continue
        selected_keys = {entry_key(item) for item in selected}
        match = next((item for item in candidates if entry_key(item) not in selected_keys), candidates[0])
        if entry_key(match) not in selected_keys:
            selected.append(match)

    return {entry_key(item) for item in selected}, unmatched


def match_requested_mod_tokens(optional_mods: list[dict[str, Any]], requested_tokens: list[str]) -> list[dict[str, Any]]:
    selected: list[dict[str, Any]] = []
    selected_keys: set[str] = set()
    for token in requested_tokens:
        matches = find_mod_matches(optional_mods, token)
        if not matches:
            raise ValueError(f"Unknown optional mod token: {token}")
        if len(matches) > 1:
            summary = ", ".join(describe_mod_match(match) for match in matches[:6])
            raise ValueError(f"Ambiguous optional mod token '{token}' matched {len(matches)} mods: {summary}")
        match = matches[0]
        key = entry_key(match)
        if key not in selected_keys:
            selected.append(match)
            selected_keys.add(key)
    return selected


def find_mod_matches(optional_mods: list[dict[str, Any]], token: str) -> list[dict[str, Any]]:
    trimmed = token.strip()
    if not trimmed:
        return []
    matches = []
    if looks_like_path_token(trimmed):
        token_key = path_key(trimmed)
        for mod in optional_mods:
            if token_key in mod_path_keys(mod):
                matches.append(mod)
        return dedupe_mods(matches)

    token_id = normalize_mod_id(trimmed)
    token_text = text_key(trimmed)
    token_file = trimmed.lower()
    token_file_stem = remove_jar_suffix(trimmed).lower()
    for mod in optional_mods:
        identifiers = {
            normalize_mod_id(mod.get("modId", "")),
            normalize_mod_id(mod.get("manifestModId", "")),
            normalize_mod_id(mod.get("launchModId", "")),
        }
        file_name = str(mod.get("fileName") or "")
        file_stem = remove_jar_suffix(file_name)
        file_candidates = {file_name.lower(), file_stem.lower()}
        name_candidates = {text_key(str(mod.get("name") or ""))}
        if (
            token_id in identifiers
            or token_file in file_candidates
            or token_file_stem in file_candidates
            or token_text in name_candidates
        ):
            matches.append(mod)
    return dedupe_mods(matches)


def build_path_match_map(optional_mods: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for mod in optional_mods:
        for key in mod_path_keys(mod):
            result.setdefault(key, mod)
    return result


def build_mod_id_match_map(optional_mods: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    result: dict[str, list[dict[str, Any]]] = {}
    for mod in optional_mods:
        for value in (mod.get("modId"), mod.get("manifestModId"), mod.get("launchModId")):
            normalized = normalize_mod_id(str(value or ""))
            if normalized:
                result.setdefault(normalized, []).append(mod)
    return result


def dedupe_mods(mods: list[dict[str, Any]]) -> list[dict[str, Any]]:
    result = []
    seen = set()
    for mod in mods:
        key = entry_key(mod)
        if key in seen:
            continue
        seen.add(key)
        result.append(mod)
    return result


def mod_path_keys(mod: dict[str, Any]) -> set[str]:
    return {
        key
        for key in (
            path_key(str(mod.get("storagePath") or "")),
            path_key(str(mod.get("relativePath") or "")),
        )
        if key
    }


def entry_key(mod: dict[str, Any]) -> str:
    return path_key(str(mod.get("storagePath") or mod.get("relativePath") or mod.get("fileName") or ""))


def describe_mod_match(mod: dict[str, Any]) -> str:
    name = str(mod.get("name") or mod.get("fileName") or "").strip()
    mod_id = str(mod.get("modId") or mod.get("manifestModId") or mod.get("launchModId") or "").strip()
    if mod_id:
        return f"{name} ({mod_id})"
    return name


def normalize_remote_storage_path(path: str, sts_root: dict[str, Any], application_id: str) -> str:
    value = path.strip().replace("\\", "/")
    root = str(sts_root.get("root") or "")
    if sts_root.get("accessMode") == "run-as" and root == "files/sts" and value.startswith("files/sts"):
        return storage_root_for_config(sts_root, application_id) + value[len("files/sts") :]
    return value


def storage_root_for_config(sts_root: dict[str, Any], application_id: str) -> str:
    root = str(sts_root.get("root") or "").rstrip("/")
    if sts_root.get("accessMode") == "run-as" and root == "files/sts":
        return f"/data/user/0/{application_id}/files/sts"
    return root


def storage_path_for_relative(storage_root: str, relative_path: str) -> str:
    return storage_root.rstrip("/") + "/" + relative_path.strip("/")


def remote_relative_path(path: str) -> str:
    value = path.strip().replace("\\", "/")
    marker = "/files/sts/"
    marker_index = value.find(marker)
    if marker_index >= 0:
        return value[marker_index + len(marker) :]
    if value.startswith("files/sts/"):
        return value[len("files/sts/") :]
    if value.startswith("sts/"):
        return value[len("sts/") :]
    return value.lstrip("/")


def path_key(path: str) -> str:
    return remote_relative_path(path).strip().lower()


def normalize_mod_id(value: str) -> str:
    return value.strip().lower()


def text_key(value: str) -> str:
    return re.sub(r"\s+", " ", value.strip().lower())


def looks_like_path_token(token: str) -> bool:
    return "/" in token or "\\" in token


def remove_jar_suffix(value: str) -> str:
    trimmed = value.strip()
    if trimmed.lower().endswith(".jar"):
        return trimmed[:-4]
    return trimmed


def parse_int(value: str) -> int | None:
    try:
        return int(value.strip())
    except (TypeError, ValueError):
        return None
