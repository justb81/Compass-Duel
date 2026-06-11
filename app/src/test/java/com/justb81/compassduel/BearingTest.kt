package com.justb81.compassduel

import com.justb81.compassduel.game.Bearing
import com.justb81.compassduel.game.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BearingTest {

    private val tolerance = 0.01f

    @Test
    fun `bearing due north is zero`() {
        val bearing = Bearing.calculate(Position(0f, 0f), Position(0f, 1f))
        assertEquals(0f, bearing, tolerance)
    }

    @Test
    fun `bearing due east is ninety degrees`() {
        val bearing = Bearing.calculate(Position(0f, 0f), Position(1f, 0f))
        assertEquals(90f, bearing, tolerance)
    }

    @Test
    fun `angular distance wraps across the 360 boundary`() {
        assertEquals(20f, Bearing.angularDistance(350f, 10f), tolerance)
    }

    @Test
    fun `aim inside tolerance is on target`() {
        assertTrue(Bearing.isOnTarget(aim = 100f, target = 120f))
        assertFalse(Bearing.isOnTarget(aim = 100f, target = 140f))
    }
}
