from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


@dataclass
class HarnessContext:
    options: Any
    repo_root: Path
    gradle_wrapper: Path | None = None
    adb_path: str | None = None
    application_id: str | None = None
    resolved_device_serial: str = ""
    operations: list[dict[str, Any]] = field(default_factory=list)
    started_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    result: dict[str, Any] = field(default_factory=dict)
    cached_out_dir: Path | None = None
