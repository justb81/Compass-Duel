package com.justb81.compassduel.game.gesture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BowDetectorTest {

    private val detector = BowDetector()
    private val aimDelta = 0.01f

    private fun sample(t: Long, pitch: Float, azimuth: Float) =
        BowSample(timestampMillis = t, pitchDegrees = pitch, azimuthDegrees = azimuth)

    /** Feeds a sequence and returns the first non-null capture, or null. */
    private fun feed(vararg samples: BowSample): Float? {
        var captured: Float? = null
        samples.forEach { s -> detector.onSample(s)?.let { captured = it } }
        return captured
    }

    @Test
    fun `a full bow captures the azimuth at onset, not at the bottom of the tilt`() {
        val captured = feed(
            sample(t = 0, pitch = 0f, azimuth = 137f), // aiming at the target
            sample(t = 50, pitch = 40f, azimuth = 120f), // onset: freezes 137° from the prior sample
            sample(t = 100, pitch = 70f, azimuth = 90f), // deep (phone faces floor, azimuth noisy)
            sample(t = 300, pitch = 10f, azimuth = 137f), // returns upright
        )
        assertEquals(137f, captured!!, aimDelta)
    }

    @Test
    fun `no capture when the tilt never reaches the deep threshold`() {
        val captured = feed(
            sample(t = 0, pitch = 0f, azimuth = 100f),
            sample(t = 50, pitch = 40f, azimuth = 100f), // onset
            sample(t = 100, pitch = 45f, azimuth = 100f), // not deep enough
            sample(t = 300, pitch = 5f, azimuth = 100f), // returns
        )
        assertNull(captured)
    }

    @Test
    fun `a bow held longer than the max duration aborts`() {
        val captured = feed(
            sample(t = 0, pitch = 0f, azimuth = 50f),
            sample(t = 50, pitch = 40f, azimuth = 50f), // onset
            sample(t = 100, pitch = 70f, azimuth = 50f), // deep
            sample(t = 5_000, pitch = 5f, azimuth = 50f), // returns far too late → aborted
        )
        assertNull(captured)
    }

    @Test
    fun `azimuth captured at onset survives wrap-around near 360`() {
        val captured = feed(
            sample(t = 0, pitch = 0f, azimuth = 359f),
            sample(t = 50, pitch = 40f, azimuth = 350f), // onset freezes 359°
            sample(t = 100, pitch = 70f, azimuth = 200f),
            sample(t = 250, pitch = 10f, azimuth = 359f),
        )
        assertEquals(359f, captured!!, aimDelta)
    }

    @Test
    fun `reset allows a second capture for greeting another opponent`() {
        val first = feed(
            sample(t = 0, pitch = 0f, azimuth = 30f),
            sample(t = 50, pitch = 40f, azimuth = 30f),
            sample(t = 100, pitch = 70f, azimuth = 30f),
            sample(t = 250, pitch = 5f, azimuth = 30f),
        )
        assertEquals(30f, first!!, aimDelta)

        detector.reset()

        var second: Float? = null
        listOf(
            sample(t = 1_000, pitch = 0f, azimuth = 200f),
            sample(t = 1_050, pitch = 40f, azimuth = 200f),
            sample(t = 1_100, pitch = 70f, azimuth = 200f),
            sample(t = 1_250, pitch = 5f, azimuth = 200f),
        ).forEach { s -> detector.onSample(s)?.let { second = it } }
        assertEquals(200f, second!!, aimDelta)
    }
}
