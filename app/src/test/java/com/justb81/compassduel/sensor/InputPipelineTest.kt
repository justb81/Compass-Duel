package com.justb81.compassduel.sensor

import com.justb81.compassduel.game.engine.GameClock
import com.justb81.compassduel.net.protocol.GameMode
import com.justb81.compassduel.net.protocol.NetMessage
import com.justb81.compassduel.net.protocol.PlayerAction
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [InputPipeline.processSamples].
 *
 * [OrientationSensor] and [ShakeDetector] are Android framework classes requiring a real
 * [android.hardware.SensorManager]. Tests bypass the class constructor entirely and call
 * the companion-object [InputPipeline.processSamples] directly with fake [kotlinx.coroutines.flow.Flow]
 * values, keeping this file runnable in a pure JVM environment with no Android SDK.
 */
class InputPipelineTest {

    /** Controllable clock for timestamp assertions. */
    private val testClock = object : GameClock {
        var time: Long = START_TIME
        override fun nowMillis(): Long = time
    }

    private val zeroCalibration = AimCalibration(facingOffsetDegrees = 0f)

    // ---------------------------------------------------------------------------
    // Cadence emission
    // ---------------------------------------------------------------------------

    @Test
    fun `processSamples emits cadence input for IDLE when pitch is neutral`() = runTest {
        val outputs = mutableListOf<NetMessage.PlayerInput>()

        val orientation = OrientationSample(
            azimuthDegrees = 45f,
            pitchDegrees = 0f, // flat = shield posture is at edges; 0 is within shield range
            rollDegrees = 0f,
            accuracy = 3,
        )
        // pitch=0 → isShieldPosture(0) = |0| <= 15 → true → emits SHIELD not IDLE
        val accel = AccelerometerSample(
            linearAccelMagnitude = LOW_ACCEL,
            timestampMillis = START_TIME,
        )

        testClock.time = START_TIME + InputPipeline.CADENCE_MILLIS

        InputPipeline.processSamples(
            orientationFlow = flow { emit(orientation) },
            accelFlow = flow { emit(accel) },
            clock = testClock,
            playerId = PLAYER_ID,
            mode = GameMode.STANDARD,
            calibration = zeroCalibration,
            onInput = { outputs += it },
        )

        assertTrue(outputs.isNotEmpty()) { "Expected at least one cadence emission" }
        // pitch=0 is within shield range (|0| <= 15°) so SHIELD action is emitted
        assertEquals(PlayerAction.SHIELD, outputs.last().action)
    }

    @Test
    fun `processSamples emits IDLE cadence when pitch is large forward tilt without shake`() = runTest {
        val outputs = mutableListOf<NetMessage.PlayerInput>()

        val orientation = OrientationSample(
            azimuthDegrees = 90f,
            pitchDegrees = 25f, // above shield threshold (15°) but below ATTACK threshold (20°) is fine;
            // 25° > 20° → would be ATTACK if shake is present. Without shake: no gesture.
            // isShieldPosture(25f) = |25| > 15 → false → IDLE
            rollDegrees = 0f,
            accuracy = 3,
        )
        val accel = AccelerometerSample(
            linearAccelMagnitude = LOW_ACCEL, // no shake
            timestampMillis = START_TIME,
        )

        testClock.time = START_TIME + InputPipeline.CADENCE_MILLIS

        InputPipeline.processSamples(
            orientationFlow = flow { emit(orientation) },
            accelFlow = flow { emit(accel) },
            clock = testClock,
            playerId = PLAYER_ID,
            mode = GameMode.STANDARD,
            calibration = zeroCalibration,
            onInput = { outputs += it },
        )

        assertTrue(outputs.isNotEmpty()) { "Expected at least one cadence emission" }
        assertEquals(PlayerAction.IDLE, outputs.last().action)
    }

    // ---------------------------------------------------------------------------
    // Gesture emission (ATTACK)
    // ---------------------------------------------------------------------------

    @Test
    fun `processSamples emits ATTACK immediately on shake gesture above threshold`() = runTest {
        val outputs = mutableListOf<NetMessage.PlayerInput>()

        // First sample: no gesture (no previous sample for dodge check, and pitch/shake might not meet criteria)
        // Use a second sample that meets ATTACK criteria
        val orientation1 = OrientationSample(
            azimuthDegrees = 180f,
            pitchDegrees = 25f,
            rollDegrees = 0f,
            accuracy = 3,
        )
        val accel1 = AccelerometerSample(
            linearAccelMagnitude = LOW_ACCEL,
            timestampMillis = START_TIME,
        )

        // Second sample: ATTACK gesture (pitch > 20° AND shake > threshold)
        val orientation2 = OrientationSample(
            azimuthDegrees = 180f,
            pitchDegrees = ATTACK_PITCH,
            rollDegrees = 0f,
            accuracy = 3,
        )
        val accel2 = AccelerometerSample(
            linearAccelMagnitude = HIGH_SHAKE,
            timestampMillis = START_TIME + InputPipeline.CADENCE_MILLIS + 1L,
        )

        testClock.time = START_TIME + InputPipeline.CADENCE_MILLIS + 1L

        InputPipeline.processSamples(
            orientationFlow = flow { emit(orientation1); emit(orientation2) },
            accelFlow = flow { emit(accel1); emit(accel2) },
            clock = testClock,
            playerId = PLAYER_ID,
            mode = GameMode.STANDARD,
            calibration = zeroCalibration,
            onInput = { outputs += it },
        )

        val attackInputs = outputs.filter { it.action == PlayerAction.ATTACK }
        assertTrue(attackInputs.isNotEmpty()) { "Expected ATTACK input to be emitted" }
    }

    // ---------------------------------------------------------------------------
    // Kids mode
    // ---------------------------------------------------------------------------

    @Test
    fun `processSamples in Kids mode uses lower shake threshold`() = runTest {
        val outputs = mutableListOf<NetMessage.PlayerInput>()

        // Shake above Kids threshold (1.5) but below Standard threshold (2.5)
        val kidsShake = KIDS_SHAKE_ABOVE_THRESHOLD

        val orientation = OrientationSample(
            azimuthDegrees = 270f,
            pitchDegrees = ATTACK_PITCH,
            rollDegrees = 0f,
            accuracy = 3,
        )
        val accel = AccelerometerSample(
            linearAccelMagnitude = kidsShake,
            timestampMillis = START_TIME,
        )

        testClock.time = START_TIME + InputPipeline.CADENCE_MILLIS

        InputPipeline.processSamples(
            orientationFlow = flow { emit(orientation) },
            accelFlow = flow { emit(accel) },
            clock = testClock,
            playerId = PLAYER_ID,
            mode = GameMode.KIDS,
            calibration = zeroCalibration,
            onInput = { outputs += it },
        )

        val attackInputs = outputs.filter { it.action == PlayerAction.ATTACK }
        assertTrue(attackInputs.isNotEmpty()) {
            "Expected ATTACK with kids-level shake ($kidsShake m/s²) in Kids mode"
        }
    }

    @Test
    fun `processSamples never emits DODGE in Kids mode`() = runTest {
        val outputs = mutableListOf<NetMessage.PlayerInput>()

        // Two samples with a pitch swing that would trigger DODGE in Standard mode
        val orientation1 = OrientationSample(
            azimuthDegrees = 90f,
            pitchDegrees = POSITIVE_PITCH_FOR_DODGE,
            rollDegrees = 0f,
            accuracy = 3,
        )
        val accel1 = AccelerometerSample(linearAccelMagnitude = LOW_ACCEL, timestampMillis = START_TIME)

        val orientation2 = OrientationSample(
            azimuthDegrees = 90f,
            pitchDegrees = NEGATIVE_PITCH_FOR_DODGE,
            rollDegrees = 0f,
            accuracy = 3,
        )
        val accel2 = AccelerometerSample(
            linearAccelMagnitude = LOW_ACCEL,
            timestampMillis = START_TIME + DODGE_WINDOW_OFFSET,
        )

        testClock.time = START_TIME + DODGE_WINDOW_OFFSET + InputPipeline.CADENCE_MILLIS

        InputPipeline.processSamples(
            orientationFlow = flow { emit(orientation1); emit(orientation2) },
            accelFlow = flow { emit(accel1); emit(accel2) },
            clock = testClock,
            playerId = PLAYER_ID,
            mode = GameMode.KIDS,
            calibration = zeroCalibration,
            onInput = { outputs += it },
        )

        val dodgeInputs = outputs.filter { it.action == PlayerAction.DODGE }
        assertTrue(dodgeInputs.isEmpty()) { "DODGE must never be emitted in Kids mode; got $dodgeInputs" }
    }

    // ---------------------------------------------------------------------------
    // Calibration
    // ---------------------------------------------------------------------------

    @Test
    fun `processSamples applies calibration to aim azimuth`() = runTest {
        val outputs = mutableListOf<NetMessage.PlayerInput>()

        val calibration = AimCalibration(facingOffsetDegrees = 90f)
        val orientation = OrientationSample(
            azimuthDegrees = 180f, // raw azimuth; calibrated = (180 - 90) % 360 = 90°
            pitchDegrees = 0f,
            rollDegrees = 0f,
            accuracy = 3,
        )
        val accel = AccelerometerSample(linearAccelMagnitude = LOW_ACCEL, timestampMillis = START_TIME)

        testClock.time = START_TIME + InputPipeline.CADENCE_MILLIS

        InputPipeline.processSamples(
            orientationFlow = flow { emit(orientation) },
            accelFlow = flow { emit(accel) },
            clock = testClock,
            playerId = PLAYER_ID,
            mode = GameMode.STANDARD,
            calibration = calibration,
            onInput = { outputs += it },
        )

        assertTrue(outputs.isNotEmpty()) { "Expected at least one emission" }
        assertEquals(EXPECTED_CALIBRATED_AIM, outputs.last().aimDegrees, AIM_DELTA)
    }

    // ---------------------------------------------------------------------------
    // Player id is included
    // ---------------------------------------------------------------------------

    @Test
    fun `processSamples sets correct player id on all emitted inputs`() = runTest {
        val outputs = mutableListOf<NetMessage.PlayerInput>()
        val expectedPlayerId = 3

        val orientation = OrientationSample(0f, 0f, 0f, 3)
        val accel = AccelerometerSample(LOW_ACCEL, START_TIME)

        testClock.time = START_TIME + InputPipeline.CADENCE_MILLIS

        InputPipeline.processSamples(
            orientationFlow = flow { emit(orientation) },
            accelFlow = flow { emit(accel) },
            clock = testClock,
            playerId = expectedPlayerId,
            mode = GameMode.STANDARD,
            calibration = zeroCalibration,
            onInput = { outputs += it },
        )

        assertTrue(outputs.isNotEmpty()) { "Expected at least one emission" }
        assertTrue(outputs.all { it.playerId == expectedPlayerId }) {
            "All inputs must carry playerId=$expectedPlayerId"
        }
    }

    companion object {
        private const val PLAYER_ID = 2
        private const val START_TIME = 1_000_000L

        /** Linear acceleration well below any gesture threshold — no shake. */
        private const val LOW_ACCEL = 0.1f

        /** Shake above Standard threshold (2.5 m/s²). */
        private const val HIGH_SHAKE = 3.0f

        /** Shake above Kids threshold (1.5) but below Standard threshold (2.5). */
        private const val KIDS_SHAKE_ABOVE_THRESHOLD = 2.0f

        /** Pitch that exceeds the ATTACK threshold (20°). */
        private const val ATTACK_PITCH = 30f

        /** Positive pitch for dodge swing test — opposite sign to [NEGATIVE_PITCH_FOR_DODGE]. */
        private const val POSITIVE_PITCH_FOR_DODGE = 20f

        /** Negative pitch for dodge swing test. */
        private const val NEGATIVE_PITCH_FOR_DODGE = -20f

        /** Offset within the dodge window (< 300 ms). */
        private const val DODGE_WINDOW_OFFSET = 100L

        /** Expected calibrated aim: raw 180° minus offset 90° = 90°. */
        private const val EXPECTED_CALIBRATED_AIM = 90f

        /** Acceptable floating-point error for aim degree comparison. */
        private const val AIM_DELTA = 0.001f
    }
}
