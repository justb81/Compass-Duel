package com.justb81.compassduel.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import com.justb81.compassduel.di.ApplicationScope
import com.justb81.compassduel.di.SensorHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A single orientation reading derived from the rotation-vector sensor.
 *
 * ### Sign convention for pitch
 * The remap `remapCoordinateSystem(AXIS_X, AXIS_Z)` is applied so that the device
 * is treated as held upright in portrait mode with the screen facing the player.
 * In this frame `getOrientation` returns a pitch that is **positive** when the
 * top of the phone tilts away from the player (aiming forward/down).
 *
 * So **tilting the top of the phone away from the player (aiming forward and
 * slightly downward, i.e. bowing toward the opponent) yields a positive value**,
 * which is the direction the greeting bow is recognised in.
 *
 * @param azimuthDegrees Magnetic north-relative heading in `[0, 360)`.
 * @param pitchDegrees Forward tilt: positive = top of phone away from player (aiming forward).
 * @param rollDegrees Sideways tilt in degrees.
 * @param accuracy Sensor accuracy constant (e.g. [SensorManager.SENSOR_STATUS_ACCURACY_HIGH]).
 */
data class OrientationSample(
    val azimuthDegrees: Float,
    val pitchDegrees: Float,
    val rollDegrees: Float,
    val accuracy: Int,
)

/**
 * Produces a hot, shared [Flow] of [OrientationSample] values from the device's
 * rotation-vector sensor.
 *
 * The physical sensor is registered once and the readings are fanned out to all collectors
 * via [shareIn] (#72): the Game VM compass, the lobby bow detector and the [InputPipeline]
 * share a single registration instead of each opening their own `callbackFlow`. Listener
 * callbacks are posted to the injected [SensorHandler] thread so the rotation-matrix math
 * runs off the main looper (#71).
 *
 * The [SensorManager] is injected so this class is easily swapped out in environments
 * without a real sensor (but it is not unit-tested directly).
 */
@Singleton
class OrientationSensor @Inject constructor(
    private val sensorManager: SensorManager,
    @SensorHandler private val sensorHandler: Handler,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /**
     * Hot, shared [Flow] of orientation readings at [SensorManager.SENSOR_DELAY_GAME] rate.
     *
     * `replay = 1` lets a late subscriber (e.g. the compass ring) receive the most recent
     * azimuth immediately; [SharingStarted.WhileSubscribed] unregisters the physical sensor
     * once the last collector leaves, after a short grace timeout to avoid churn across
     * screen transitions.
     */
    private val shared: SharedFlow<OrientationSample> = cold().shareIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = SENSOR_STOP_TIMEOUT_MILLIS),
        replay = 1,
    )

    /** Returns the shared orientation stream. */
    fun samples(): Flow<OrientationSample> = shared

    private fun cold(): Flow<OrientationSample> = callbackFlow {
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: run { close(); return@callbackFlow }

        val rotationMatrix = FloatArray(MATRIX_SIZE)
        val remappedMatrix = FloatArray(MATRIX_SIZE)
        val orientationValues = FloatArray(ORIENTATION_COMPONENTS)
        // Captured by the listener closure and accessed from the sensor callback thread by both
        // onSensorChanged (read) and onAccuracyChanged (write). @Volatile only applies to fields,
        // not captured locals, so use AtomicInteger for the cross-thread visibility guarantee.
        val currentAccuracy = AtomicInteger(SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.remapCoordinateSystem(
                    rotationMatrix,
                    SensorManager.AXIS_X,
                    SensorManager.AXIS_Z,
                    remappedMatrix,
                )
                SensorManager.getOrientation(remappedMatrix, orientationValues)

                val azimuth = (Math.toDegrees(orientationValues[0].toDouble()).toFloat() + FULL_CIRCLE) % FULL_CIRCLE
                // In this remap getOrientation returns a pitch that is positive when the top of the
                // phone tilts away from the player (aiming forward / bowing toward the opponent),
                // matching OrientationSample.pitchDegrees — no negation required.
                val pitch = Math.toDegrees(orientationValues[1].toDouble()).toFloat()
                val roll = Math.toDegrees(orientationValues[2].toDouble()).toFloat()

                trySend(OrientationSample(azimuth, pitch, roll, currentAccuracy.get()))
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                currentAccuracy.set(accuracy)
            }
        }

        sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME, sensorHandler)
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    companion object {
        private const val MATRIX_SIZE = 9
        private const val ORIENTATION_COMPONENTS = 3
        private const val FULL_CIRCLE = 360f

        /** Grace period before the physical sensor is unregistered after the last collector leaves. */
        private const val SENSOR_STOP_TIMEOUT_MILLIS = 2_000L
    }
}
