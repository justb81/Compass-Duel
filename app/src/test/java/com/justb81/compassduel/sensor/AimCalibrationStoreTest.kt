package com.justb81.compassduel.sensor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AimCalibrationStoreTest {

    @Test
    fun `starts empty`() {
        val store = AimCalibrationStore()
        assertNull(store.calibration.value)
        assertFalse(store.isCalibrated)
    }

    @Test
    fun `capture stores the offset and marks calibrated`() {
        val store = AimCalibrationStore()
        store.capture(123f)
        assertEquals(AimCalibration(123f), store.calibration.value)
        assertTrue(store.isCalibrated)
    }

    @Test
    fun `capture overwrites a previous calibration`() {
        val store = AimCalibrationStore()
        store.capture(10f)
        store.capture(250f)
        assertEquals(AimCalibration(250f), store.calibration.value)
    }

    @Test
    fun `clear resets to uncalibrated`() {
        val store = AimCalibrationStore()
        store.capture(90f)
        store.clear()
        assertNull(store.calibration.value)
        assertFalse(store.isCalibrated)
    }
}
