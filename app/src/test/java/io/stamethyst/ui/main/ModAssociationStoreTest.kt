package io.stamethyst.ui.main

import io.stamethyst.model.ModItemUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModAssociationStoreTest {
    @Test
    fun associatedModsFor_excludesSourceAndKeepsCurrentModOrder() {
        val alpha = createMod("C:\\mods\\Alpha.jar", modId = "alpha")
        val beta = createMod("C:\\mods\\Beta.jar", modId = "beta")
        val gamma = createMod("C:\\mods\\Gamma.jar", modId = "gamma")
        val state = ModAssociationState(
            groups = listOf(
                ModAssociationGroup(
                    id = "group-a",
                    colorArgb = 0xFF64B5F6.toInt(),
                    modKeys = setOf(alpha.storagePath, beta.storagePath, gamma.storagePath)
                )
            )
        )

        val associated = state.associatedModsFor(alpha, listOf(gamma, alpha, beta))
        val badge = state.badgeFor(alpha)

        assertEquals(listOf(gamma, beta), associated)
        assertEquals(2, badge?.associatedCount)
    }

    @Test
    fun sanitizeModAssociationState_rewritesLegacyRuntimePathToCurrentLibraryPath() {
        val currentPath = "/storage/emulated/0/Android/data/io.stamethyst/files/sts/mods_library/TestMod.jar"
        val legacyRuntimePath = "/storage/emulated/0/Android/data/io.stamethyst/files/sts/mods/TestMod.jar"
        val otherPath = "C:\\mods\\Other.jar"
        val currentMod = createMod(currentPath, modId = "test")
        val otherMod = createMod(otherPath, modId = "other")
        val state = ModAssociationState(
            groups = listOf(
                ModAssociationGroup(
                    id = "group-a",
                    colorArgb = 0xFF81C784.toInt(),
                    modKeys = setOf(legacyRuntimePath, otherPath)
                ),
                ModAssociationGroup(
                    id = "group-b",
                    colorArgb = 0xFFFFB74D.toInt(),
                    modKeys = setOf("missing.jar", otherPath)
                )
            )
        )

        val sanitized = sanitizeModAssociationState(state, listOf(currentMod, otherMod))

        assertEquals(1, sanitized.groups.size)
        assertEquals(setOf(currentPath, otherPath), sanitized.groups.single().modKeys)
        assertTrue(sanitized.groups.none { it.id == "group-b" })
    }

    private fun createMod(
        storagePath: String,
        modId: String = "testmod",
        manifestModId: String = modId
    ): ModItemUi {
        return ModItemUi(
            modId = modId,
            manifestModId = manifestModId,
            storagePath = storagePath,
            name = modId,
            version = "1.0.0",
            description = "",
            dependencies = emptyList(),
            required = false,
            installed = true,
            enabled = false,
            explicitPriority = null,
            effectivePriority = null
        )
    }
}
