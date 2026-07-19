#!/usr/bin/env python3
"""Download JDK 8 JFR configuration templates (default.jfc / profile.jfc).

These files are missing from the Android runtime pack (jre8-pojav) but are
required by jdk.jfr.Configuration / Arthas `jfr start`.

Fetched by streaming the public Adoptium Temurin 8 JRE tarball and extracting
only lib/jfr/*.jfc (not committed; see .gitignore jdk-companion/).
"""

from __future__ import annotations

import json
import sys
import tarfile
from pathlib import Path
from typing import Iterable
from urllib.request import Request, urlopen

_ADOPTIUM_LATEST = (
    "https://api.adoptium.net/v3/assets/latest/8/hotspot"
    "?architecture=x64&image_type=jre&os=linux&vendor=eclipse"
)

# Fallback if Adoptium API is unavailable (known-good Temurin 8u492).
_FALLBACK_JRE_TGZ = (
    "https://github.com/adoptium/temurin8-binaries/releases/download/"
    "jdk8u492-b09/OpenJDK8U-jre_x64_linux_hotspot_8u492b09.tar.gz"
)

_JFC_NAMES = ("default.jfc", "profile.jfc")

_RESOURCE_DIR = Path(__file__).resolve().parent / "resource"
_DEST_DIR = _RESOURCE_DIR / "jdk-companion" / "jfr"

_UA = "SlayTheAmethyst-arthas-jfr-jfc/1.0"


def is_jfr_jfc_present() -> bool:
    return all((_DEST_DIR / name).is_file() for name in _JFC_NAMES)


def _http_get(url: str, timeout: int = 120):
    req = Request(url, headers={"User-Agent": _UA})
    return urlopen(req, timeout=timeout)


def _resolve_jre_tgz_url() -> str:
    try:
        with _http_get(_ADOPTIUM_LATEST, timeout=30) as resp:
            assets = json.loads(resp.read().decode("utf-8"))
        if not assets:
            raise RuntimeError("empty Adoptium assets list")
        link = assets[0]["binary"]["package"]["link"]
        if not link:
            raise RuntimeError("missing package link in Adoptium response")
        return link
    except Exception as exc:
        print(f"[warn] Adoptium API failed ({exc}); using fallback tarball URL")
        return _FALLBACK_JRE_TGZ


def _extract_jfc_from_tgz(url: str, dest_dir: Path) -> dict[str, Path]:
    dest_dir.mkdir(parents=True, exist_ok=True)
    found: dict[str, Path] = {}
    print(f"Downloading JFR .jfc from Temurin 8 JRE (stream extract)...")
    print(f"  source: {url}")
    with _http_get(url, timeout=180) as resp:
        with tarfile.open(fileobj=resp, mode="r|gz") as tar:
            for member in tar:
                base = Path(member.name).name
                if base not in _JFC_NAMES or not member.isfile():
                    continue
                if not member.name.endswith(f"/lib/jfr/{base}"):
                    continue
                extracted = tar.extractfile(member)
                if extracted is None:
                    continue
                data = extracted.read()
                out = dest_dir / base
                out.write_bytes(data)
                found[base] = out
                print(f"  wrote {out} ({len(data)} bytes)")
                if len(found) >= len(_JFC_NAMES):
                    break
    missing = [n for n in _JFC_NAMES if n not in found]
    if missing:
        raise RuntimeError(f"JFR .jfc not found in tarball: {missing}")
    return found


def download_jfr_jfc() -> list[str]:
    if is_jfr_jfc_present():
        paths = [str(_DEST_DIR / n) for n in _JFC_NAMES]
        print(f"JFR .jfc already present under {_DEST_DIR}")
        return paths
    url = _resolve_jre_tgz_url()
    found = _extract_jfc_from_tgz(url, _DEST_DIR)
    return [str(found[n]) for n in _JFC_NAMES]


def jfr_jfc_paths() -> Iterable[Path]:
    return (_DEST_DIR / name for name in _JFC_NAMES)


def main() -> int:
    if is_jfr_jfc_present():
        print(f"JFR .jfc already present: {_DEST_DIR}")
        for p in jfr_jfc_paths():
            print(f"  {p} ({p.stat().st_size} bytes)")
        return 0
    download_jfr_jfc()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
