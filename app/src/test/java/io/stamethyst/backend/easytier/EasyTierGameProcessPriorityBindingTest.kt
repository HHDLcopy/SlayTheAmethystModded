package io.stamethyst.backend.easytier

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyTierGameProcessPriorityBindingTest {
    @Test
    fun bindFlags_requestImportantBindingForLmkPriorityAlignment() {
        assertTrue(
            "BIND_IMPORTANT is what hoists :easytier into the game's oom_adj band.",
            EasyTierGameProcessPriorityBinding.BIND_FLAGS and Context.BIND_IMPORTANT != 0
        )
    }

    @Test
    fun bindFlags_omitAutoCreateSoBindingNeverSpawnsAnIdleEasyTierProcess() {
        // Auto-create would start :easytier on every game launch, spending memory on the very
        // low-memory devices this binding exists to protect.
        assertEquals(
            0,
            EasyTierGameProcessPriorityBinding.BIND_FLAGS and Context.BIND_AUTO_CREATE
        )
    }

    @Test
    fun bindFlags_omitAdjustWithActivitySoPriorityHoldsWhileTheGameRunsUnfocused() {
        // BIND_ADJUST_WITH_ACTIVITY would let the hoist decay when the game Activity loses focus,
        // which is exactly when a backgrounded game still needs its session alive.
        assertEquals(
            0,
            EasyTierGameProcessPriorityBinding.BIND_FLAGS and Context.BIND_ADJUST_WITH_ACTIVITY
        )
    }
}
