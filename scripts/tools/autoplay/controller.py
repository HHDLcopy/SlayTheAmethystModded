from typing import Any


class AutoplayController:

    def __init__(self, agent_client: Any) -> None:
        self._agent = agent_client

    def play_card(self) -> dict[str, Any]:
        return self._agent.execute("PLAY_CARD", {})

    def end_turn(self) -> None:
        self._agent.execute("END_TURN", {})

    def press_proceed(self) -> None:
        self._agent.execute("PRESS_PROCEED", {})

    def skip_room(self) -> None:
        self._agent.execute("SKIP_ROOM", {})

    def wait(self, ms: int) -> None:
        self._agent.execute("WAIT", {"ms": ms})

    def set_mode(self, mode: str) -> None:
        self._agent.execute("MODE_COMMAND", {"mode": mode})
