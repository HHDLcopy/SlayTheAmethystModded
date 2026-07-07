#!/usr/bin/env python3
"""Build libprocfs_cpu.so for Android aarch64.

Requires: Android NDK 27+ installed via SDK manager.

NDK detection order:
  1. ANDROID_NDK_HOME or ANDROID_NDK env var
  2. ANDROID_SDK_ROOT/ndk/<latest>  or  ANDROID_HOME/ndk/<latest>
  3. sdk.dir from project root local.properties
"""

from __future__ import annotations

import os
import platform
import shlex
import shutil
import subprocess
import sys
from pathlib import Path

_SCRIPT = Path(__file__).resolve()
_PROJECT_ROOT = _SCRIPT.parents[3]
_BRIDGE_DIR = _PROJECT_ROOT / "arthas-bridge" / "src" / "main" / "jni"
_SRC = _BRIDGE_DIR / "procfs_cpu.c"
_OUT = _BRIDGE_DIR / "libprocfs_cpu.so"
_LOCAL_PROPS = _PROJECT_ROOT / "local.properties"

_SYSTEM = platform.system()
_PREBUILT_KIND = {
    "Linux": "linux-x86_64",
    "Darwin": "darwin-x86_64",
    "Windows": "windows-x86_64",
}.get(_SYSTEM)

_EXE_SUFFIX = ".exe" if _SYSTEM == "Windows" else ""
_TARGET = "aarch64-linux-android26-clang" + _EXE_SUFFIX


def _find_ndk() -> Path | None:
    for key in ("ANDROID_NDK_HOME", "ANDROID_NDK"):
        val = os.environ.get(key)
        if val:
            p = Path(val)
            if (p / "toolchains").is_dir():
                return p
            for child in p.iterdir():
                if child.is_dir() and (child / "toolchains").is_dir():
                    return child

    sdk_root = _find_sdk_root()
    if sdk_root:
        ndk_dir = sdk_root / "ndk"
        if ndk_dir.is_dir():
            versions = sorted(
                (d for d in ndk_dir.iterdir() if d.is_dir()),
                key=lambda d: [int(x) for x in d.name.split(".")],
                reverse=True,
            )
            if versions:
                return versions[0]
    return None


def _find_sdk_root() -> Path | None:
    for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        val = os.environ.get(key)
        if val and Path(val).is_dir():
            return Path(val)

    if _LOCAL_PROPS.is_file():
        for line in _LOCAL_PROPS.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line.startswith("sdk.dir=") or line.startswith("sdk.dir ="):
                val = line.split("=", 1)[1].strip()
                p = Path(val)
                if p.is_dir():
                    return p

    return None


def _find_clang(ndk: Path) -> Path | None:
    if _PREBUILT_KIND is None:
        print(f"Error: unsupported platform {_SYSTEM}", file=sys.stderr)
        return None

    clang = ndk / "toolchains" / "llvm" / "prebuilt" / _PREBUILT_KIND / "bin" / _TARGET
    return clang if clang.is_file() else None


def main() -> int:
    ndk = _find_ndk()
    if ndk is None:
        print(
            "Error: NDK not found. Set ANDROID_NDK_HOME or ANDROID_SDK_ROOT.",
            file=sys.stderr,
        )
        return 1
    print(f"NDK: {ndk}")

    clang = _find_clang(ndk)
    if clang is None:
        print(
            f"Error: {_TARGET} not found under {ndk}/toolchains/llvm/prebuilt/",
            file=sys.stderr,
        )
        return 1
    print(f"Clang: {clang}")

    _OUT.unlink(missing_ok=True)

    cmd = [
        str(clang),
        "-shared",
        "-o", str(_OUT),
        str(_SRC),
        "-Wall", "-Wextra", "-O2", "-s",
    ]

    print(f"Compiling: {' '.join(map(shlex.quote, cmd))}")
    result = subprocess.run(cmd, cwd=str(_PROJECT_ROOT))

    if result.returncode != 0:
        print("Compilation failed.", file=sys.stderr)
        return result.returncode

    print(f"Built: {_OUT}  ({_OUT.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
