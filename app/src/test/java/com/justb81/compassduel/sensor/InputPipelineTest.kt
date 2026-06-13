package com.justb81.compassduel.sensor

import com.justb81.compassduel.game.engine.GameClock
import com.justb81.compassduel.game.gesture.GestureThresholds
import com.justb81.compassduel.net.protocol.GameMode
import com.justb81.compassduel.net.protocol.NetMessage
import com.justb81.compassduel.net.protocol.PlayerAction
import kotlinx.coroutines.flow.emptyFlow
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
 * The [testClock] is advanced from inside the orientation flow so successive samples carry
 * increasing timestamps — this is how the shield-arming hold (>= 1 s upright + steady) is
 * driven deterministically.
 */
class InputPipelineTest {

    /** Controllable clock for timestamp assertions. */
    private val testClock = object : GameClock {
        var time: Long = START_TIME
        override fun nowMillis(): Long = time
    }

    private fun upright(azimuth: Float = 0f) =
        OrientationSample(azimuthDegrees = azimuth, pitchDegrees = 0f, rollDegrees = 0f, accuracy = 3)

    /**
     * A clock that advances [stepMillis] on every call, so successive merged samples carry
     * increasing timestamps deterministically (independent of coroutine interleaving).
     */
    private fun steppingClock(stepMillis: Long) = object : GameClock {
        private var calls = 0
        override fun nowMillis(): Long = START_TIME + calls++ * stepMillis
    }

    // ---------------------------------------------------------------------------
    // Cadence emission — shield state
    // ---------------------------------------------------------------------------

    @Test
    fun `cadence emits IDLE while the shield has not yet armed`() = runTest {
        val outputs = mutableListOf<NetMessage.PlayerInput>()
        testClock.time = START_TIME + InputPipeline.CADENCE_MILLIS

        // A single upright+steady sample starts arming but cannot reach the 1 s hold.
        InputPipeline.processSamples(
            orientationFlow = flow { emit(upright()) },
            accelFlow = emptyFlow(),
            clock = testClock,
            playerId = PLAYER_ID,
            mode = GameMode.STANDARD,
            onInput = { outputs += it },
        )

        assertTrue(outputs.isNotEmpty()) { "Expected at least one cadence emission" }
        assertEquals(PlayerAction.IDLE, outputs.last().action)
    }

    @Test
    fun `cadence emits SHIELD once the upright-steady hold completes`() = runTest {
        val outputs = mutableListOf<NetMessage.PlayerInput>()
        // Stamp each successive sample one hold-window apart so the 2nd activates the shield.
        val clock = steppingClock(GestureThresholds.SHIELD_HOLD_MILLIS)

        InputPipeline.processSamples(
            orientationFlow = flow {
                emit(upright())
                emit(upright())
            },
            accelFlow = emptyFlow(),
            clock = clock,
            playerId = PLAYER_ID,
            mode = GameMode.STANDARD,
            onInput = { outputs += it },
        )

        assertEquals(PlayerAction.SHIELD, outputs.last().action)
    }

    // ---------------------------------------------------------------------------
    // FIRE → ATTACK
    // ---------------------------------------------------------------------------

    @Test
    fun `a swing emits ATTACK immediately`() = runTest {
        val outputs = mutableListOf<NetMessage.PlayerInput>()
        testClock.time = START_TIME + InputPipeline.CADENCE_MILLIS

        InputPipeline.processSamples(
            orientationFlow = flow { emit(upright()) },
            accelFlow = flow { emit(AccelerometerSample(linearAccelMagnitude = HIGH_SWING)) },
            clock = testClock,
            playerId = PLAYER_ID,
            mode = GameMode.STANDARD,
            onInput = { outputs += it },
        )

        assertTrue(outputs.any { it.action == PlayerAction.ATTACK }) { "Expected an ATTACK from the swing" }
    }

    @Test
    fun `kids mode fires on a softer swing than standard`() = runTest {
        val standardOutputs = mutableListOf<NetMessage.PlayerInput>()
        val kidsOutputs = mutableListOf<NetMessage.PlayerInput>()
        testClock.time = START_TIME + InputPipeline.CADENCE_MILLIS

        for ((mode, sink) in listOf(GameMode.STANDARD to standardOutputs, GameMode.KIDS to kidsOutputs)) {
            InputPipeline.processSamples(
                orientationFlow = flow { emit(upright()) },
                accelFlow = flow { emit(AccelerometerSample(linearAccelMagnitude = KIDS_SWING_ABOVE_THRESHOLD)) },
                clock = testClock,
                playerId = PLAYER_ID,
                mode = mode,
                onInput = { sink += it },
            )
        }

        assertTrue(standardOutputs.none { it.action == PlayerAction.ATTACK }) { "Standard ignores the soft swing" }
        assertTrue(kidsOutputs.any { it.action == PlayerAction.ATTACK }) { "Kids fires on the soft swing" }
    }

    // ---------------------------------------------------------------------------
    // Shield arming progress callback
    // ---------------------------------------------------------------------------

    @Test
    fun `onShieldArmProgress reports full progress once the shield activates`() = runTest {
        val progresses = mutableListOf<Float>()
        val clock = steppingClock(GestureThresholds.SHIELD_HOLD_MILLIS)

        InputPipeline.processSamples(
            orientationFlow = flow {
                emit(upright())
                emit(upright())
            },
            accelFlow = emptyFlow(),
            clock = clock,
            playerId = PLAYER_ID,
            mode = GameMode.STANDARD,
            onInput = { },
            onShieldArmProgress = { progresses += it },
        )

        assertEquals(1f, progresses.last(), AIM_DELTA)
    }

    // ---------------------------------------------------------------------------
    // Raw azimuth & player id
    // ---------------------------------------------------------------------------

    @Test
    fun `processSamples reports the raw azimuth as aim`() = runTest {
        val outputs = mutableListOf<NetMessage.PlayerInput>()
        testClock.time = START_TIME + InputPipeline.CADENCE_MILLIS

        InputPipeline.processSamples(
            orientationFlow = flow { emit(upright(azimuth = 180f)) },
            accelFlow = flow { emit(AccelerometerSample(LOW_ACCEL)) },
            clock = testClock,
            playerId = PLAYER_ID,
            mode = GameMode.STANDARD,
            onInput = { outputs += it },
        )

        assertTrue(outputs.isNotEmpty()) { "Expected at least one emission" }
        assertEquals(EXPECTED_RAW_AIM, outputs.last().aimDegrees, AIM_DELTA)
    }

    @Test
    fun `processSamples sets correct player id on all emitted inputs`() = runTest {
        val outputs = mutableListOf<NetMessage.PlayerInput>()
        val expectedPlayerId = 3
        testClock.time = START_TIME + InputPipeline.CADENCE_MILLIS

        InputPipeline.processSamples(
            orientationFlow = flow { emit(upright()) },
            accelFlow = flow { emit(AccelerometerSample(LOW_ACCEL)) },
            clock = testClock,
            playerId = expectedPlayerId,
            mode = GameMode.STANDARD,
            onInput = { outputs += it },
        )

        assertTrue(outputs.isNotEmpty()) { "Expected at least one emission" }
        assertTrue(outputs.all { it.playerId == expectedPlayerId }) {
            "All inputs must carry playerId=$expectedPlayerId"
        }
    }

    // ---------------------------------------------------------------------------
    // Merge-stream guarantee (Issue #70): one physical swing → at most one ATTACK
    // ---------------------------------------------------------------------------

    @Test
    fun `single swing spike with multiple orientation updates emits ATTACK at most once`() = runTest {
        val outputs = mutableListOf<NetMessage.PlayerInput>()

        val accelFlow = flow { emit(AccelerometerSample(linearAccelMagnitude = HIGH_SWING)) }
        val orientationFlow = flow {
            repeat(REPEAT_ORIENTATION_UPDATES) { emit(upright()) }
        }

        testClock.time = START_TIME + InputPipeline.CADENCE_MILLIS + 1L

        InputPipeline.processSamples(
            orientationFlow = orientationFlow,
            accelFlow = accelFlow,
            clock = testClock,
            playerId = PLAYER_ID,
            mode = GameMode.STANDARD,
            onInput = { outputs += it },
        )

        val attackInputs = outputs.filter { it.action == PlayerAction.ATTACK }
        assertTrue(attackInputs.size <= 1) {
            "Expected at most one ATTACK from a single swing spike; got ${attackInputs.size}"
        }
    }

    companion object {
        private const val PLAYER_ID = 2
        private const val START_TIME = 1_000_000L

        /** Linear acceleration well below any gesture threshold — no swing. */
        private const val LOW_ACCEL = 0.1f

        /** Swing above Standard threshold (2.5 m/s²). */
        private const val HIGH_SWING = 3.0f

        /** Swing above Kids threshold (1.5) but below Standard threshold (2.5). */
        private const val KIDS_SWING_ABOVE_THRESHOLD = 2.0f

        /** Expected raw aim: the azimuth is reported unchanged. */
        private const val EXPECTED_RAW_AIM = 180f

        /** Acceptable floating-point error for aim degree comparison. */
        private const val AIM_DELTA = 0.001f

        /** Number of orientation updates to emit after a single swing spike in the duplicate test. */
        private const val REPEAT_ORIENTATION_UPDATES = 5
    }
}
