package io.stamethyst.backend.easytier

import org.junit.Assert.assertEquals
import org.junit.Test

class EasyTierRoomSelectionStoreTest {
    @Test
    fun writeAndRead_roundTripsPreferredRoomId() {
        val roots = EasyTierTestRoots.create("easytier-room-selection-store")
        try {
            EasyTierRoomSelectionStore.write(
                roots.context,
                EasyTierRoomSelectionSnapshot(preferredRoomId = "room-alpha")
            )

            val restored = EasyTierRoomSelectionStore.read(roots.context)
            assertEquals("room-alpha", restored.preferredRoomId)
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }
}
