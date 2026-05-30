package io.stamethyst.config

enum class BootOverlayAnimation(val persistedValue: String) {
    CARD_SHUFFLE("card_shuffle"),
    INFINITY_ORBIT("infinity_orbit"),
    COMET("comet"),
    WAVE("wave"),
    HALO("halo"),
    ELASTIC_DOTS("elastic_dots"),
    SPIRAL("spiral"),
    PULSE_RINGS("pulse_rings"),
    ORBITAL_ECLIPSE("orbital_eclipse"),
    RUNIC_GATE("runic_gate"),
    PRISM_SWEEP("prism_sweep"),
    HELIX_LADDER("helix_ladder"),
    LIQUID_ORB("liquid_orb"),
    SIGNAL_STACK("signal_stack"),
    DIAMOND_FLOW("diamond_flow"),
    GRAVITY_WELL("gravity_well");

    companion object {
        fun fromPersistedValue(value: String?): BootOverlayAnimation? {
            return entries.firstOrNull { it.persistedValue == value }
        }
    }
}