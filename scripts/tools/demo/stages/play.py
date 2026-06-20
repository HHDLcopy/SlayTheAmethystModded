"""Stage 2 — Interactive card play.

Three-phase flow:
  1. Take control — switch play monitor to COMMAND_DRIVEN
  2. Play — wait for hand, play 3 cards, end turn
  3. Return control — switch back to AUTONOMOUS
"""

from __future__ import annotations

import json
import time
from typing import TYPE_CHECKING

from .base import Stage

if TYPE_CHECKING:
    from ..demo_runner import DemoRunner


MAX_WAIT_SECONDS = 30


class PlayStage(Stage):
    id = "play"
    name = "Interactive Card Play"

    def run(self, runner: DemoRunner, out_dir: str) -> dict:
        proto = runner._proto
        bridge = runner._bridge

        # ── 1. Take control ──────────────────────────────────────
        agents = bridge.list_agents()
        play_id = next((a["id"] for a in agents if a["spec"].startswith("play")), None)
        if not play_id:
            play_id = bridge.attach("play")
        runner._log_op("PLAY_MODE", "switch to COMMAND_DRIVEN")
        proto.execute("MODE_COMMAND", {"mode": "COMMAND_DRIVEN"})

        # ── 2. Wait for combat with a non-empty hand ─────────────
        hand = []
        start = time.time()
        while not hand and (time.time() - start) < MAX_WAIT_SECONDS:
            state = proto.observe()
            hand = state.get("combat", {}).get("hand", [])
            if not hand:
                proto.execute("WAIT", {"ms": 500})
                time.sleep(0.8)
        wait_seconds = round(time.time() - start, 1)
        runner._log_op(
            "PLAY_WAIT",
            f"waited {wait_seconds}s, hand={len(hand)} cards"
        )

        if not hand:
            # Give up: return control and report failure
            proto.execute("MODE_COMMAND", {"mode": "AUTONOMOUS"})
            runner._log_op("PLAY_MODE", "switch back to AUTONOMOUS (gave up)")
            return {
                "success": False,
                "status": "IDLE",
                "message": f"No hand after {wait_seconds}s wait in {state.get('mode','?')} / {state.get('screen','?')}",
                "data": {"wait_seconds": wait_seconds, "state_snapshot": state},
            }

        # ── 3. Play 3 rounds ─────────────────────────────────────
        rounds: list[dict] = []
        total_played = 0

        for rnd in range(3):
            before = proto.observe()
            proto.execute("PLAY_CARD", {})
            time.sleep(0.6)
            after = proto.observe()

            before_hand = _hand_ids(before)
            after_hand = _hand_ids(after)
            played_ids = list(set(before_hand) - set(after_hand))

            entry = {
                "before_energy": before.get("combat", {}).get("energy"),
                "after_energy": after.get("combat", {}).get("energy"),
                "before_hand_size": len(before_hand),
                "after_hand_size": len(after_hand),
                "played_card_ids": played_ids,
            }
            rounds.append(entry)
            if played_ids:
                total_played += len(played_ids)

            with open(f"{out_dir}/round-{rnd}-before.json", "w", encoding="utf-8") as f:
                json.dump(before, f, indent=2)
            with open(f"{out_dir}/round-{rnd}-after.json", "w", encoding="utf-8") as f:
                json.dump(after, f, indent=2)
            runner._log_op(
                f"ROUND {rnd} play_card",
                f"hand {len(before_hand)}→{len(after_hand)} played={played_ids}",
            )

        # End turn
        proto.execute("END_TURN", {})
        time.sleep(0.6)
        post_turn = proto.observe()
        runner._log_op("END_TURN", f"screen={post_turn.get('screen','?')} hand={len(_hand_ids(post_turn))}")

        # ── 4. Return control ────────────────────────────────────
        proto.execute("MODE_COMMAND", {"mode": "AUTONOMOUS"})
        runner._log_op("PLAY_MODE", "switch back to AUTONOMOUS")

        summary = {
            "rounds": rounds,
            "total_played": total_played,
            "post_turn_screen": post_turn.get("screen", "?"),
            "wait_seconds": wait_seconds,
        }
        with open(f"{out_dir}/play-summary.json", "w", encoding="utf-8") as f:
            json.dump(summary, f, indent=2)

        return {
            "success": total_played > 0,
            "status": "PLAYED" if total_played > 0 else "IDLE",
            "message": f"{total_played} cards played across 3 rounds (waited {wait_seconds}s for combat)",
            "data": summary,
        }


def _hand_ids(state: dict) -> list[str]:
    try:
        return [c["id"] for c in state.get("combat", {}).get("hand", [])]
    except (KeyError, TypeError):
        return []
