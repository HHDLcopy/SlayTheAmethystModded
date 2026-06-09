package io.stamethyst.backend.presence

enum class GamePresenceState(
    val wireValue: String
) {
    Launcher("launcher"),
    Game("game")
}
