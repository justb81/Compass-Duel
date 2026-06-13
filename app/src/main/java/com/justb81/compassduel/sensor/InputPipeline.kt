package com.justb81.compassduel.sensor

import com.justb81.compassduel.game.engine.GameClock
import com.justb81.compassduel.game.gesture.GestureClassifier
import com.justb81.compassduel.game.gesture.GestureEvent
import com.justb81.compassduel.game.gesture.GestureThresholds
import com.justb81.compassduel.game.gesture.MotionSample
import com.justb81.compassduel.game.kids.KidsRules
import com.justb81.compassduel.net.protocol.GameMode
import com.justb81.compassduel.net.protocol.NetMessage
import com.justb81.compassduel.net.protocol.PlayerAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Combines orientation and accelerometer sensor flows into [NetMessage.PlayerInput]
 * messages for the local player.
 *
 * ### Emission cadence
 * - **Every [CADENCE_MILLIS] ms**: emits the latest aim/pitch/shield state (action = IDLE or SHIELD).
 * - **Immediately on gesture**: emits ATTACK or DODGE (standard only) without waiting
 *   for the next cadence tick.
 *
 * ### Mode configuration
 * - **Standard**: [GestureThresholds.STANDARD_SHAKE_MPS2] shake threshold; dodge enabled.
 * - **Kids**: [KidsRules.SHAKE_THRESHOLD_MPS2] shake threshold; dodge disabled (DODGE never emitted).
 *
 * ### Merge-stream design (Issue #70)
 * Orientation and accelerometer samples are merged into a single typed event stream via
 * [merge]. Each physical sensor sample is processed exactly once: an [Accel] event
 * triggers classification with the latest known orientation; an [Orientation] event
 * updates the stored orientation state and triggers a classification pass using the
 * latest known accelerometer magnitude. The latest value from the other sensor is held
 * in a local variable, so no sample is ever re-evaluated. This eliminates the
 * stale-pairing and duplicate-classification bugs caused by [kotlinx.coroutines.flow.combine].
 *
 * ### Testability
 * The combining logic is exposed as the companion-object function [processSamples] — a pure
 * suspend function that drives the classifier from external flows. Tests call it directly
 * without needing real [OrientationSensor] or [ShakeDetector] instances (which require Android SDK).
 *
 * @param orientationSensor Source of orientation readings.
 * @param shakeDetector Source of accelerometer readings.
 * @param clock Provides timestamps for gesture samples.
 */
class InputPipeline @Inject constructor(
    private val orientationSensor: OrientationSensor,
    private val shakeDetector: ShakeDetector,
    private val clock: GameClock,
) {

    private var pipelineJob: Job? = null

    /**
     * Starts the sensor pipeline for [playerId].
     *
     * @param scope The coroutine scope that owns the pipeline.
     * @param playerId The local player's id, included in every [NetMessage.PlayerInput].
     * @param mode The active game mode (controls gesture thresholds).
     * @param calibration Aim calibration captured at round start.
     * @param onInput Callback invoked for each produced [NetMessage.PlayerInput].
     */
    fun start(
        scope: CoroutineScope,
        playerId: Int,
        mode: GameMode,
        calibration: AimCalibration,
        onInput: (NetMessage.PlayerInput) -> Unit,
    ) {
        stop()
        pipelineJob = scope.launch {
            processSamples(
                orientationFlow = orientationSensor.samples(),
                accelFlow = shakeDetector.samples(),
                clock = clock,
                playerId = playerId,
                mode = mode,
                calibration = calibration,
                onInput = onInput,
            )
        }
    }

    /** Stops the pipeline and cancels all running jobs. */
    fun stop() {
        pipelineJob?.cancel()
        pipelineJob = null
    }

    companion object {

        /** Minimum interval between cadence-based emissions (milliseconds). */
        const val CADENCE_MILLIS = 100L

        /**
         * Tagged union used inside the merged sensor stream so each physical sample
         * can be identified and processed exactly once.
         */
        private sealed interface SensorEvent {
            /** A new orientation reading arrived. */
            data class Orientation(val sample: OrientationSample) : SensorEvent

            /** A new accelerometer reading arrived. */
            data class Accel(val sample: AccelerometerSample) : SensorEvent
        }

        /**
         * Pure combining logic — drives the [GestureClassifier] from [orientationFlow] and
         * [accelFlow] and invokes [onInput] on each output.
         *
         * Orientation and accelerometer samples are merged into a single event stream so each
         * physical sample is processed exactly once. The latest value from the other sensor is
         * retained in a local variable; classification runs immediately when either sensor fires.
         *
         * Exposed as a companion-object function for unit testing: pass fake flows and assert
         * on [onInput] calls without needing a real sensor stack or Android framework classes.
         *
         * @param orientationFlow Source of [OrientationSample] readings.
         * @param accelFlow Source of [AccelerometerSample] readings.
         * @param clock Provides epoch millis for gesture timing.
         * @param playerId Local player identifier.
         * @param mode Game mode (controls classifier configuration).
         * @param calibration Aim offset for normalizing azimuth.
         * @param onInput Invoked for each [NetMessage.PlayerInput] produced.
         */
        @Suppress("LongParameterList")
        suspend fun processSamples(
            orientationFlow: Flow<OrientationSample>,
            accelFlow: Flow<AccelerometerSample>,
            clock: GameClock,
            playerId: Int,
            mode: GameMode,
            calibration: AimCalibration,
            onInput: (NetMessage.PlayerInput) -> Unit,
        ) {
            val shakeThreshold = when (mode) {
                GameMode.STANDARD -> GestureThresholds.STANDARD_SHAKE_MPS2
                GameMode.KIDS -> KidsRules.SHAKE_THRESHOLD_MPS2
            }
            val dodgeEnabled = mode == GameMode.STANDARD
            val classifier = GestureClassifier(shakeThreshold, dodgeEnabled)

            var lastCadenceMillis = 0L

            // Latest values from each sensor — held so the other sensor's event can pair with them.
            var latestOrientation: OrientationSample? = null
            var latestAccelMagnitude: Float = 0f

            val orientationEvents = orientationFlow.map { SensorEvent.Orientation(it) }
            val accelEvents = accelFlow.map { SensorEvent.Accel(it) }

            merge(orientationEvents, accelEvents).collect { event ->
                // Update the stored value for whichever sensor just fired.
                when (event) {
                    is SensorEvent.Orientation -> latestOrientation = event.sample
                    is SensorEvent.Accel -> latestAccelMagnitude = event.sample.linearAccelMagnitude
                }

                // Wait until we have at least one orientation reading so azimuth/pitch are valid.
                val orientation = latestOrientation ?: return@collect

                val now = clock.nowMillis()
                val motionSample = MotionSample(
                    timestampMillis = now,
                    pitchDegrees = orientation.pitchDegrees,
                    rollDegrees = orientation.rollDegrees,
                    linearAccelMagnitude = latestAccelMagnitude,
                )

                val gesture = classifier.onSample(motionSample)
                val isShielding = classifier.isShieldPosture(orientation.pitchDegrees)
                val calibratedAim = calibration.calibrate(orientation.azimuthDegrees)

                if (gesture != null) {
                    val action = when (gesture) {
                        GestureEvent.ATTACK -> PlayerAction.ATTACK
                        GestureEvent.DODGE -> PlayerAction.DODGE
                    }
                    onInput(
                        NetMessage.PlayerInput(
                            playerId = playerId,
                            aimDegrees = calibratedAim,
                            pitchDegrees = orientation.pitchDegrees,
                            action = action,
                            clientTimeMillis = now,
                        ),
                    )
                    lastCadenceMillis = now
                }

                if (now - lastCadenceMillis >= CADENCE_MILLIS) {
                    lastCadenceMillis = now
                    val cadenceAction = if (isShielding) PlayerAction.SHIELD else PlayerAction.IDLE
                    onInput(
                        NetMessage.PlayerInput(
                            playerId = playerId,
                            aimDegrees = calibratedAim,
                            pitchDegrees = orientation.pitchDegrees,
                            action = cadenceAction,
                            clientTimeMillis = now,
                        ),
                    )
                }
            }
        }
    }
}
