package com.justb81.compassduel.game

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MovementPolicyTest {

    @Test
    fun `a few steps within tolerance is OK`() {
        assertEquals(MovementVerdict.OK, MovementPolicy.evaluate(MovementPolicy.WARN_STEPS - 1, false))
    }

    @Test
    fun `steps at the warn threshold produce a warning`() {
        assertEquals(MovementVerdict.WARN, MovementPolicy.evaluate(MovementPolicy.WARN_STEPS, false))
    }

    @Test
    fun `steps at the forfeit threshold force a forfeit`() {
        assertEquals(MovementVerdict.FORFEIT, MovementPolicy.evaluate(MovementPolicy.FORFEIT_STEPS, false))
    }

    @Test
    fun `a significant-motion event forfeits immediately regardless of step count`() {
        assertEquals(MovementVerdict.FORFEIT, MovementPolicy.evaluate(0, true))
    }
}
