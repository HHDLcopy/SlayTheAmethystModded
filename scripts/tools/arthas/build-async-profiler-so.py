#!/usr/bin/env python3
"""Build libasyncProfiler.so for Android aarch64.

Builds async-profiler from source, cross-compiling for aarch64-linux-android.
Requires: Android NDK 27+, JDK 8+ for JVM TI headers.

Output: scripts/tools/arthas/resource/async-profiler/libasyncProfiler-linux-arm64.so
"""

from __future__ import annotations

import os
import platform
import shlex
import subprocess
import sys
from pathlib import Path

_SCRIPT = Path(__file__).resolve()
_PROJECT_ROOT = _SCRIPT.parents[3]
_RESOURCE_DIR = _SCRIPT.parent / "resource" / "async-profiler"
_TMP_DIR = _PROJECT_ROOT / "agent-tmp" / "async-profiler"
_LOCAL_PROPS = _PROJECT_ROOT / "local.properties"
_OUT_SO = _RESOURCE_DIR / "libasyncProfiler-linux-arm64.so"

_SYSTEM = platform.system()
_PREBUILT_KIND = {
    "Linux": "linux-x86_64",
    "Darwin": "darwin-x86_64",
    "Windows": "windows-x86_64",
}.get(_SYSTEM)

_API = 26
_TARGET = f"aarch64-linux-android{_API}"
_EXE_SUFFIX = ".exe" if _SYSTEM == "Windows" else ""

_ASPROF_REPO = "https://github.com/async-profiler/async-profiler.git"
_ASPROF_TAG = "v3.0"


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


def _find_toolchain(ndk: Path) -> Path | None:
    if _PREBUILT_KIND is None:
        print(f"Error: unsupported platform {_SYSTEM}", file=sys.stderr)
        return None
    return ndk / "toolchains" / "llvm" / "prebuilt" / _PREBUILT_KIND / "bin"


def _find_jdk() -> Path | None:
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        p = Path(java_home)
        if (p / "include" / "jvmti.h").exists():
            return p
    for cmd in ("java",):
        result = _run(["which", cmd], capture=True, check=False)
        if result.returncode == 0:
            p = Path(result.stdout.strip()).resolve()
            p = p.parent.parent
            if (p / "include" / "jvmti.h").exists():
                return p
    for candidate in ("/usr/lib/jvm/java-8-openjdk", "/usr/lib/jvm/default-java"):
        p = Path(candidate)
        if (p / "include" / "jvmti.h").exists():
            return p
    return None


def _run(cmd: list[str], cwd=None, capture=False, check=True):
    kwargs = {}
    if capture:
        kwargs["stdout"] = subprocess.PIPE
        kwargs["stderr"] = subprocess.PIPE
        kwargs["text"] = True
    result = subprocess.run(cmd, cwd=cwd, **kwargs)
    if check and result.returncode != 0:
        print(f"Command failed: {' '.join(shlex.quote(str(c)) for c in cmd)}", file=sys.stderr)
        if capture:
            print(result.stderr, file=sys.stderr)
        sys.exit(result.returncode)
    return result


def _patch_source(src_dir: Path) -> None:
    sym_file = src_dir / "src" / "symbols_linux.cpp"
    content = sym_file.read_text()
    if "musl = true; // Android Bionic" in content:
        print("Source already patched.")
        return
    content = content.replace(
        "musl = confstr(_CS_GNU_LIBC_VERSION, NULL, 0) == 0 && errno != 0;",
        "musl = true; // Android Bionic\n        (void)errno;",
    )
    sym_file.write_text(content)
    print("Patched symbols_linux.cpp for Android Bionic.")


def main() -> int:
    ndk = _find_ndk()
    if ndk is None:
        print("Error: NDK not found.", file=sys.stderr)
        return 1
    print(f"NDK: {ndk}")

    tc = _find_toolchain(ndk)
    if tc is None:
        print("Error: toolchain not found.", file=sys.stderr)
        return 1

    clangxx = tc / f"{_TARGET}-clang++{_EXE_SUFFIX}"
    if not clangxx.is_file():
        print(f"Error: {clangxx} not found.", file=sys.stderr)
        return 1
    print(f"CXX: {clangxx}")

    jdk = _find_jdk()
    if jdk is None:
        print("Error: JDK with jvmti.h not found. Set JAVA_HOME.", file=sys.stderr)
        return 1
    print(f"JDK: {jdk}")

    if not _TMP_DIR.is_dir():
        print(f"Cloning {_ASPROF_REPO} tag {_ASPROF_TAG} ...")
        _TMP_DIR.parent.mkdir(parents=True, exist_ok=True)
        _run(["git", "clone", "--depth", "1", "--branch", _ASPROF_TAG, _ASPROF_REPO, str(_TMP_DIR)])
    else:
        print(f"Using existing source at {_TMP_DIR}")

    _patch_source(_TMP_DIR)

    build_dir = _TMP_DIR / "build" / "lib"
    build_dir.mkdir(parents=True, exist_ok=True)
    out_so = build_dir / "libasyncProfiler.so"
    out_so.unlink(missing_ok=True)

    src_dir = _TMP_DIR / "src"
    src_files = sorted(src_dir.glob("*.cpp"))
    if not src_files:
        print("Error: no source files found.", file=sys.stderr)
        return 1

    includes = (
        f"-I{jdk}/include",
        f"-I{jdk}/include/linux",
        f"-I{src_dir}/helper",
    )

    cxxflags = [
        "-O3", "-fno-exceptions", "-fno-omit-frame-pointer",
        "-fvisibility=hidden", "-fPIC", "-shared",
        f"-DPROFILER_VERSION=\"3.0\"",
        *includes,
    ]

    generate = "\n".join(f'#include "{f}"' for f in src_files)

    cmd = [str(clangxx), *cxxflags, "-o", str(out_so), "-xc++", "-", "-ldl"]

    print("Compiling async-profiler (merged compilation unit, ~50 sources)...")
    result = subprocess.run(
        cmd,
        input=generate,
        text=True,
        cwd=str(_TMP_DIR),
        capture_output=True,
    )

    if result.returncode != 0:
        errors = [l for l in (result.stderr or "").splitlines() if "error:" in l]
        if errors:
            print(f"Compilation errors ({len(errors)}):", file=sys.stderr)
            for e in errors[:10]:
                print(f"  {e}", file=sys.stderr)
        else:
            print(result.stderr, file=sys.stderr)
        return result.returncode

    _RESOURCE_DIR.mkdir(parents=True, exist_ok=True)
    _OUT_SO.unlink(missing_ok=True)
    import shutil
    shutil.copy2(out_so, _OUT_SO)

    size = _OUT_SO.stat().st_size
    print(f"Built: {_OUT_SO} ({size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
