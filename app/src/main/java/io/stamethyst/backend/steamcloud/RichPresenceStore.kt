package io.stamethyst.backend.steamcloud

/**
 * In-process singleton that carries the latest rich-presence key-value snapshot
 * from [io.stamethyst.GameSessionCoordinator] (which reads the IPC file written by
 * the mod) to [SteamGamePresenceService] (which uploads it every heartbeat cycle).
 *
 * Thread-safe through @Volatile on the single reference; both sides do only a
 * single pointer-swap, so no heavier synchronization is needed.
 */
object RichPresenceStore {
    @Volatile private var _current: Map<String, String>? = null

    /** Replace the current snapshot. Called from the main thread by GameSessionCoordinator. */
    fun update(kvPairs: Map<String, String>) {
        _current = kvPairs
    }

    /** Returns the latest snapshot, or null if none has been received yet. */
    fun current(): Map<String, String>? = _current

    /** Clear state when the game session ends. */
    fun clear() {
        _current = null
    }
}
