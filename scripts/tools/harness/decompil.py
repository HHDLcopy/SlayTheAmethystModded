import hashlib
import urllib.request
from pathlib import Path
from typing import Any

from scripts.tools.lib.sts_harness import (
    limit_text,
    parse_decompil_target,
    quote_android_shell,
)
from scripts.tools.harness._context import HarnessContext
from scripts.tools.harness._device import (
    remote_sts_root_script,
    resolve_device_sts_root,
)
from scripts.tools.harness._runner import adb, run_native


def _jar_library_dir(ctx: HarnessContext) -> Path:
    return ctx.repo_root / "debug-artifacts" / "harness" / "jar-library"


def _compute_local_sha256(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        while True:
            chunk = stream.read(1 << 20)
            if not chunk:
                break
            hasher.update(chunk)
    return hasher.hexdigest()


def _read_local_sha256(jar_path: Path) -> str | None:
    sha_path = Path(str(jar_path) + ".sha256")
    if not sha_path.exists():
        return None
    text = sha_path.read_text(encoding="utf-8").strip()
    return text or None


def _write_local_sha256(jar_path: Path, digest: str) -> None:
    sha_path = Path(str(jar_path) + ".sha256")
    sha_path.write_text(digest.strip() + "\n", encoding="utf-8")


def _remote_file_sha256(ctx: HarnessContext, sts_root: dict[str, Any], relative_path: str) -> str | None:
    trimmed = relative_path.lstrip("/")
    root_path = str(sts_root["root"])
    remote_path = root_path if not trimmed else f"{root_path}/{trimmed}"
    quoted = quote_android_shell(remote_path)
    script = f"""if [ -f {quoted} ]; then
  sha=''
  if command -v sha256sum >/dev/null 2>&1; then
    sha=$(sha256sum {quoted} | cut -d' ' -f1 2>/dev/null)
  elif command -v md5sum >/dev/null 2>&1; then
    sha="md5:$(md5sum {quoted} | cut -d' ' -f1 2>/dev/null)"
  elif command -v md5 >/dev/null 2>&1; then
    sha="md5:$(md5 {quoted} | sed 's/.*[[:space:]]//')"
  fi
  echo "sha256=$sha"
  echo "exists=1"
else
  echo "exists=0"
fi
"""
    result = remote_sts_root_script(ctx, sts_root, script, timeout_seconds=30, allow_failure=True)
    sha_value = ""
    for line in result.output.splitlines():
        stripped = line.strip()
        if stripped.startswith("sha256="):
            sha_value = stripped[len("sha256="):]
        elif stripped == "exists=0":
            return None
    if not sha_value:
        return None
    return sha_value


def _pull_jar_if_needed(ctx: HarnessContext, sts_root: dict[str, Any], remote_relative: str, local_path: Path) -> None:
    remote_key = remote_relative.lstrip("/")
    remote_hash = _remote_file_sha256(ctx, sts_root, remote_key)
    if remote_hash is not None and local_path.exists():
        local_hash = _read_local_sha256(local_path)
        if local_hash == remote_hash:
            print(f"Jar {remote_key} unchanged (SHA-256 match), skipping pull.")
            return
    remote_full = f"{sts_root['root']}/{remote_key}"
    local_path.parent.mkdir(parents=True, exist_ok=True)
    if sts_root["accessMode"] == "run-as":
        result = adb(
            ctx,
            [
                "exec-out", "run-as", ctx.application_id or "", "sh", "-c",
                f"cat {quote_android_shell(remote_full)}",
            ],
            timeout_seconds=600,
            allow_failure=True,
            capture="binary",
            local_path=str(local_path),
        )
        if result.exit_code != 0 or not local_path.exists() or local_path.stat().st_size <= 0:
            raise RuntimeError(f"Failed to pull {remote_full} from device via run-as (exit {result.exit_code}).")
    else:
        adb(ctx, ["pull", remote_full, str(local_path)], timeout_seconds=600)
    if not local_path.exists():
        raise RuntimeError(f"Failed to pull {remote_full} from device.")
    local_digest = _compute_local_sha256(local_path)
    _write_local_sha256(local_path, local_digest)


def _ensure_cfr(ctx: HarnessContext) -> Path:
    cfr_path = ctx.repo_root / "scripts" / "tools" / "lib" / "cfr.jar"
    if cfr_path.exists() and cfr_path.stat().st_size > 0:
        return cfr_path
    cfr_url = "https://repo1.maven.org/maven2/org/benf/cfr/0.152/cfr-0.152.jar"
    print(f"Downloading CFR from {cfr_url} ...")
    try:
        urllib.request.urlretrieve(cfr_url, str(cfr_path))
    except Exception as exc:
        raise RuntimeError(f"Failed to download CFR jar from {cfr_url}: {exc}")
    if not cfr_path.exists() or cfr_path.stat().st_size <= 0:
        raise RuntimeError(f"CFR jar download produced an empty or missing file: {cfr_path}")
    return cfr_path


def run_decompil(ctx: HarnessContext, resolved_out_dir: Path) -> tuple[dict[str, Any], bool, str, str]:
    targets = ctx.options.decompil_targets
    if not targets:
        raise ValueError("At least one -Target is required for decompil command.")
    cfr_path = _ensure_cfr(ctx)
    sts_root = resolve_device_sts_root(ctx)
    jar_dir = _jar_library_dir(ctx)
    jar_dir.mkdir(parents=True, exist_ok=True)
    desktop_jar_local = jar_dir / "desktop-1.0.jar"
    _pull_jar_if_needed(ctx, sts_root, "desktop-1.0.jar", desktop_jar_local)
    src_dir = resolved_out_dir / "src"
    src_dir.mkdir(parents=True, exist_ok=True)
    decompiled_filenames: list[str] = []
    for target in targets:
        class_name, _method_name = parse_decompil_target(target)
        args = ["-jar", str(cfr_path), str(desktop_jar_local), class_name, "--outputdir", str(src_dir)]
        result = run_native(ctx, "java", args, timeout_seconds=180, allow_failure=False)
        if result.exit_code == 0:
            decompiled_filenames.append(f"{class_name}.java")
    info: dict[str, Any] = {
        "targets": targets,
        "desktopJarLocal": str(desktop_jar_local),
        "stsRoot": sts_root,
        "outputSrcDir": str(src_dir),
        "decompiledClasses": decompiled_filenames,
    }
    if not decompiled_filenames:
        return info, False, "ERROR", "Decompilation produced no output files."
    return info, True, "OK", f"{len(decompiled_filenames)} class(es) decompiled to {src_dir}"
