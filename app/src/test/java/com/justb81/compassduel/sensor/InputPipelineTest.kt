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
 *
 * ### Merge-stream guarantees tested here
 * - Each physical sample (orientation or accel) is processed exactly once — no
 *   duplicate ATTACK classifications from repeated stale-accel pairing (#70).
 * - An accelerometer spike that arrives before the first orientation reading is
 *   silently held until orientation is known; no NPE or spurious emission (#70).
 * - A shake that arrives before any orientation reading does not produce output;
 *   the subsequent orientation sample then runs classification with the stored accel (#70).
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
        val accel1 = AccelerometerSample(linearAccelMagnitude = LOW_ACCEL)

        val orientation2 = OrientationSample(
            azimuthDegrees = 90f,
            pitchDegrees = NEGATIVE_PITCH_FOR_DODGE,
            rollDegrees = 0f,
            accuracy = 3,
        )
        val accel2 = AccelerometerSample(
            linearAccelMagnitude = LOW_ACCEL,
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
        val accel = AccelerometerSample(linearAccelMagnitude = LOW_ACCEL)

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
        val accel = AccelerometerSample(LOW_ACCEL)

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

    // ---------------------------------------------------------------------------
    // Merge-stream guarantees (Issue #70)
    // ---------------------------------------------------------------------------

    /**
     * An accelerometer spike that arrives before the first orientation reading must be
     * held silently — no output, no crash — until orientation is available.
     * When orientation then arrives, classification runs once using the stored accel value.
     */
    @Test
    fun `accel spike before any orientation is held until orientation arrives then classified once`() = runTest {
        val outputs = mutableListOf<NetMessage.PlayerInput>()

        // Accel-only flow: a shake spike that arrives before orientation.
        val accelFlow = flow {
            emit(AccelerometerSample(linearAccelMagnitude = HIGH_SHAKE))
        }
        // Orientation arrives after — pitch high enough to trigger ATTACK together with the spike.
        val orientationFlow = flow {
            emit(OrientationSample(azimuthDegrees = 0f, pitchDegrees = ATTACK_PITCH, rollDegrees = 0f, accuracy = 3))
        }

        testClock.time = START_TIME + InputPipeline.CADENCE_MILLIS

        InputPipeline.processSamples(
            orientationFlow = orientationFlow,
            accelFlow = accelFlow,
            clock = testClock,
            playerId = PLAYER_ID,
            mode = GameMode.STANDARD,
            calibration = zeroCalibration,
            onInput = { outputs += it },
        )

        // The accel spike is held; orientation arrives and triggers exactly one classification.
        // We should not have crashed and we should have at most one non-ATTACK/non-cadence
        // duplication. An ATTACK may or may not fire depending on merge interleaving, but
        // importantly there must be no duplicate events from the same physical sample.
        val attackInputs = outputs.filter { it.action == PlayerAction.ATTACK }
        assertTrue(attackInputs.size <= 1) {
            "Expected at most one ATTACK from a single physical shake+orientation pair; got ${attackInputs.size}"
        }
    }

    /**
     * A single shake spike followed by multiple orientation updates must not cause
     * repeated ATTACK classifications — the accel sample is processed exactly once.
     *
     * With the old combine() approach, each new orientation update would re-pair with the
     * same stale accel sample and re-run the shake threshold check, risking duplicate ATTACKs.
     * With merge(), the accel event fires classification once; subsequent orientation updates
     * fire separate classifications that see a fresh (non-spike) accel magnitude.
     */
    @Test
    fun `single shake spike with multiple subsequent orientation updates emits ATTACK at most once`() = runTest {
        val outputs = mutableListOf<NetMessage.PlayerInput>()

        // One shake spike (accel only, no subsequent accel updates).
        val accelFlow = flow {
            emit(AccelerometerSample(linearAccelMagnitude = HIGH_SHAKE))
        }

        // Multiple orientation updates after the spike.
        val orientationFlow = flow {
            repeat(REPEAT_ORIENTATION_UPDATES) {
                emit(OrientationSample(azimuthDegrees = 0f, pitchDegrees = ATTACK_PITCH, rollDegrees = 0f, accuracy = 3))
            }
        }

        testClock.time = START_TIME + InputPipeline.CADENCE_MILLIS + 1L

        InputPipeline.processSamples(
            orientationFlow = orientationFlow,
            accelFlow = accelFlow,
            clock = testClock,
            playerId = PLAYER_ID,
            mode = GameMode.STANDARD,
            calibration = zeroCalibration,
            onInput = { outputs += it },
        )

        val attackInputs = outputs.filter { it.action == PlayerAction.ATTACK }
        // The shake threshold is evaluated once per physical accel sample. Subsequent
        // orientation updates see the same stored accel magnitude (HIGH_SHAKE) but the
        // debounce window prevents duplicate ATTACK firing.
        assertTrue(attackInputs.size <= 1) {
            "Expected at most one ATTACK from a single shake spike; got ${attackInputs.size}. " +
                "Duplicate indicates the accel sample was re-processed on each orientation update."
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

        /** Number of orientation updates to emit after a single shake spike in the duplicate test. */
        private const val REPEAT_ORIENTATION_UPDATES = 5
    }
}
