package io.stamethyst.backend.mods

internal object ReservedCoreModComponents {
    const val BASEMOD = "BaseMod"
    const val STSLIB = "StSLib"
    const val MTS = "ModTheSpire"
    const val AMETHYST_RUNTIME_COMPAT = "Amethyst Runtime Compat"
    const val AMETHYST_FLOATING_TOOLS = "Amethyst Floating Tools"
    const val RAM_SAVER = "Ram Saver"

    fun resolveDisplayName(modId: String): String? {
        return when (ModManager.normalizeModId(modId)) {
            ModManager.MOD_ID_BASEMOD -> BASEMOD
            ModManager.MOD_ID_STSLIB -> STSLIB
            ModManager.MOD_ID_AMETHYST_RUNTIME_COMPAT -> AMETHYST_RUNTIME_COMPAT
            ModManager.MOD_ID_AMETHYST_FLOATING_TOOLS -> AMETHYST_FLOATING_TOOLS
            ModManager.MOD_ID_RAM_SAVER -> RAM_SAVER
            "modthespire" -> MTS
            else -> null
        }
    }
}
