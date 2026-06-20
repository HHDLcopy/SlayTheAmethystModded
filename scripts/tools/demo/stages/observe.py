"""Stage 1 — Game state observation.

Attaches the 'play' monitor (if not already active) and takes
several OBSERVE snapshots at 1-second intervals.
"""

from __future__ import annotations

import json
import time
from typing import TYPE_CHECKING

from .base import Stage

if TYPE_CHECKING:
    from ..demo_runner import DemoRunner


class ObserveStage(Stage):
    id = "observe"
    name = "Game State Observation"

    def run(self, runner: DemoRunner, out_dir: str) -> dict:
        proto = runner._proto
        bridge = runner._bridge

        # 1. ensure play monitor attached
        agents = bridge.list_agents()
        play_id = next((a["id"] for a in agents if a["spec"].startswith("play")), None)
        if not play_id:
            play_id = bridge.attach("play")
            runner._log_op("ATTACH play {}", f"OK {play_id}")

        # 2. take 3 snapshots
        snapshots: list[dict] = []
        for i in range(3):
            state = proto.observe()
            snapshots.append(state)
            path = f"{out_dir}/observe-{i}.json"
            with open(path, "w", encoding="utf-8") as f:
                json.dump(state, f, indent=2)
            runner._log_op(f"OBSERVE #{i}", json.dumps(state, indent=2)[:500])
            if i < 2:
                time.sleep(0.6)

        # 3. interpret
        state = snapshots[-1]
        combat = state.get("combat", {})
        hand = combat.get("hand", [])
        monsters = combat.get("monsters", [])
        room = state.get("room", {})
        map_info = state.get("map", {})

        summary = {
            "mode": state.get("mode", "?"),
            "screen": state.get("screen", "?"),
            "isScreenUp": state.get("isScreenUp", False),
            "room": room.get("type", "?"),
            "hand_size": len(hand),
            "hand_cards": [c.get("id", "?") for c in hand],
            "monsters_alive": [m for m in monsters if not m.get("dead")],
            "map_current": map_info.get("current"),
        }
        with open(f"{out_dir}/summary.json", "w", encoding="utf-8") as f:
            json.dump(summary, f, indent=2)

        in_combat = (
            combat.get("active")
            and state.get("isScreenUp") is False
            and len(hand) > 0
        )

        return {
            "success": True,
            "status": "COMBAT_ACTIVE" if in_combat else "OBSERVED",
            "message": (
                f"{state.get('mode')} | screen={state.get('screen')} | "
                f"room={room.get('type')} | hand={len(hand)} | monsters={len(monsters)}"
            ),
            "data": {"snapshots": snapshots, "summary": summary},
        }
