#!/usr/bin/env python3
"""Download libjvm.debuginfo from GitHub Release.

Places the file in resource/jdk-companion/aarch64/libjvm.debuginfo.
The file is a public release asset — no authentication needed.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path
from urllib.request import urlretrieve

_RELEASE_URL = (
    "https://github.com/ZJustin117/angelauramc-openjdk-build/"
    "releases/download/jdk8-companion/libjvm.debuginfo"
)

_RESOURCE_DIR = Path(__file__).resolve().parent / "resource"
_DEST_DIR = _RESOURCE_DIR / "jdk-companion" / "aarch64"


def is_companion_present() -> bool:
    return (_DEST_DIR / "libjvm.debuginfo").is_file()


def download_companion() -> str:
    _DEST_DIR.mkdir(parents=True, exist_ok=True)
    dest = _DEST_DIR / "libjvm.debuginfo"
    print(f"Downloading libjvm.debuginfo from GitHub Release...")
    urlretrieve(_RELEASE_URL, dest)
    size = dest.stat().st_size
    print(f"Downloaded: {dest} ({size} bytes)")
    return str(dest)


def main() -> int:
    if is_companion_present():
        print(f"Companion file already exists: {_DEST_DIR / 'libjvm.debuginfo'}")
        return 0
    download_companion()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
