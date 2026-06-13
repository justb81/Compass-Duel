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
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detects device shakes by reading the accelerometer and computing net linear acceleration.
 *
 * Each sample contains the magnitude of linear acceleration (`|a| − gravity`) so
 * [com.justb81.compassduel.game.gesture.GestureClassifier] can apply its threshold without
 * knowing the sensor origin.
 *
 * Timestamps are intentionally omitted here. [InputPipeline] stamps every [MotionSample]
 * via the injected [com.justb81.compassduel.game.engine.GameClock] so all timing goes through
 * a single, test-controllable source.
 *
 * @param linearAccelMagnitude `|sqrt(x² + y² + z²) − GRAVITY_EARTH|` in m/s².
 */
data class AccelerometerSample(
    val linearAccelMagnitude: Float,
)

/**
 * Produces a [Flow] of [AccelerometerSample] values from the device's accelerometer.
 *
 * Registers at [SensorManager.SENSOR_DELAY_GAME] and unregisters on cancellation.
 */
@Singleton
class ShakeDetector @Inject constructor(
    private val sensorManager: SensorManager,
) {

    /**
     * Cold [Flow] of accelerometer readings at [SensorManager.SENSOR_DELAY_GAME] rate.
     */
    fun samples(): Flow<AccelerometerSample> = callbackFlow {
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: run { close(); return@callbackFlow }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val magnitude = sqrt(x * x + y * y + z * z)
                val linearMagnitude = abs(magnitude - SensorManager.GRAVITY_EARTH)
                trySend(AccelerometerSample(linearMagnitude))
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, accelSensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sensorManager.unregisterListener(listener) }
    }
}
