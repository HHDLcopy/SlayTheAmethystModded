from __future__ import annotations

import sys
from pathlib import Path

# Ensure scripts/tools/ is first on sys.path so 'demo' resolves as a package
_tools_dir = str(Path(__file__).resolve().parents[1])
if _tools_dir not in sys.path:
    sys.path.insert(0, _tools_dir)

from demo.demo_cli import main  # noqa: E402


if __name__ == "__main__":
    raise SystemExit(main())
