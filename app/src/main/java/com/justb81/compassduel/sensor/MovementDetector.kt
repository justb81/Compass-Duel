package com.justb81.compassduel.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import com.justb81.compassduel.di.SensorHandler
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A physical-movement event detected on the local device.
 *
 * @param stepDelta Steps detected since the previous event (0 for a significant-motion trigger).
 * @param significant True when the platform reported a significant-motion event.
 */
data class MovementEvent(
    val stepDelta: Int = 0,
    val significant: Boolean = false,
)

/**
 * Produces a [Flow] of [MovementEvent]s from the device's step detector
 * ([Sensor.TYPE_STEP_DETECTOR]) and significant-motion trigger
 * ([Sensor.TYPE_SIGNIFICANT_MOTION]).
 *
 * These detect a player physically *relocating* (getting up and walking) — which
 * invalidates the seat bearings captured by the greeting handshake. GPS is too coarse
 * for a sub-metre seat change and the gyroscope only senses rotation, not translation,
 * so neither is used here.
 *
 * The step detector requires the `ACTIVITY_RECOGNITION` runtime permission (API 29+);
 * when it is unavailable the flow degrades gracefully to significant-motion only (which
 * needs no permission). Like [OrientationSensor], this class is not unit-tested directly —
 * the movement *policy* in [com.justb81.compassduel.game.MovementPolicy] is the tested part.
 */
@Singleton
class MovementDetector @Inject constructor(
    private val sensorManager: SensorManager,
    @SensorHandler private val sensorHandler: Handler,
) {

    /** Cold flow of movement events; completes when the collector's scope is cancelled. */
    fun events(): Flow<MovementEvent> = callbackFlow {
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val significantSensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

        val stepListener = stepSensor?.let {
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    // TYPE_STEP_DETECTOR fires once per step; values[0] == 1.0.
                    trySend(MovementEvent(stepDelta = event.values.firstOrNull()?.toInt() ?: 1))
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
            }
        }

        // Significant motion is one-shot: it must be re-armed after each trigger.
        val triggerListener = significantSensor?.let {
            object : TriggerEventListener() {
                override fun onTrigger(event: TriggerEvent) {
                    trySend(MovementEvent(significant = true))
                    sensorManager.requestTriggerSensor(this, it)
                }
            }
        }

        if (stepListener != null && stepSensor != null) {
            sensorManager.registerListener(stepListener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)
        }
        if (triggerListener != null && significantSensor != null) {
            sensorManager.requestTriggerSensor(triggerListener, significantSensor)
        }

        awaitClose {
            if (stepListener != null) sensorManager.unregisterListener(stepListener)
            if (triggerListener != null && significantSensor != null) {
                sensorManager.cancelTriggerSensor(triggerListener, significantSensor)
            }
        }
    }
}
