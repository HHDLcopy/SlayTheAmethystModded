"""Demo stage base class.

Each stage mirrors a harness_* method extracted to an independent class.
A stage has a single entry point (`run`) and a post-condition check (`verify`).
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from ..demo_runner import DemoRunner


class Stage(ABC):
    """One demo stage.  Equivalent to a harness_* method extracted to a class."""

    id: str          # "setup", "observe", "play", ...
    name: str        # "Environment Check", "Game State Observation", ...

    @abstractmethod
    def run(self, runner: DemoRunner, out_dir: str) -> dict:
        """Execute this stage.

        Args:
            runner: The DemoRunner instance holding the live connection.
            out_dir: Writable directory for this stage's artifacts.

        Returns:
            A dict following the harness result convention:
              { "success": bool, "status": str, "message": str, "data": {...} }
        """
        ...

    def verify(self, result: dict) -> bool:
        """Post-condition check.  True = stage passed."""
        return result.get("success", False)
