package com.justb81.compassduel

import com.justb81.compassduel.game.Bearing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BearingTest {

    private val tolerance = 0.01f

    @Test
    fun `angular distance wraps across the 360 boundary`() {
        assertEquals(20f, Bearing.angularDistance(350f, 10f), tolerance)
    }

    @Test
    fun `angular distance is symmetric`() {
        assertEquals(
            Bearing.angularDistance(10f, 350f),
            Bearing.angularDistance(350f, 10f),
            tolerance,
        )
    }

    @Test
    fun `aim inside tolerance is on target`() {
        assertTrue(Bearing.isOnTarget(aim = 100f, target = 120f))
        assertFalse(Bearing.isOnTarget(aim = 100f, target = 140f))
    }
}
