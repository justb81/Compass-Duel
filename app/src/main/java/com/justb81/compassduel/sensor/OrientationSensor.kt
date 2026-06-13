package com.justb81.compassduel.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A single orientation reading derived from the rotation-vector sensor.
 *
 * ### Sign convention for pitch
 * The remap `remapCoordinateSystem(AXIS_X, AXIS_Z)` is applied so that the device
 * is treated as held upright in portrait mode with the screen facing the player.
 * In this frame `getOrientation` returns a raw pitch that is **negative** when the
 * top of the phone tilts away from the player (aiming forward/down).
 *
 * [pitchDegrees] inverts this sign so that **tilting the top of the phone away from
 * the player (aiming forward and slightly downward) yields a positive value**,
 * matching the gesture-classifier convention where a positive pitch triggers an ATTACK.
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
 * Produces a [Flow] of [OrientationSample] values from the device's rotation-vector sensor.
 *
 * The sensor is registered when the flow is collected and unregistered when collection
 * ends (via `awaitClose`). The [SensorManager] is injected so this class is easily
 * swapped out in environments without a real sensor (but it is not unit-tested directly).
 */
@Singleton
class OrientationSensor @Inject constructor(
    private val sensorManager: SensorManager,
) {

    /**
     * Returns a cold [Flow] of orientation readings at [SensorManager.SENSOR_DELAY_GAME] rate.
     *
     * The flow completes when the collector's scope is cancelled.
     */
    fun samples(): Flow<OrientationSample> = callbackFlow {
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: run { close(); return@callbackFlow }

        val rotationMatrix = FloatArray(MATRIX_SIZE)
        val remappedMatrix = FloatArray(MATRIX_SIZE)
        val orientationValues = FloatArray(ORIENTATION_COMPONENTS)
        @Volatile var currentAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM

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
                // Raw pitch from getOrientation is negative when tilting forward in this remap;
                // negate so positive pitch = top of phone away from player (aiming forward).
                val pitch = -Math.toDegrees(orientationValues[1].toDouble()).toFloat()
                val roll = Math.toDegrees(orientationValues[2].toDouble()).toFloat()

                trySend(OrientationSample(azimuth, pitch, roll, currentAccuracy))
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                currentAccuracy = accuracy
            }
        }

        sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    companion object {
        private const val MATRIX_SIZE = 9
        private const val ORIENTATION_COMPONENTS = 3
        private const val FULL_CIRCLE = 360f
    }
}
