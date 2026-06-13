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
 * Produces a hot, shared [Flow] of [AccelerometerSample] values from the device's accelerometer.
 *
 * The physical sensor is registered once and fanned out to all collectors via [shareIn] (#72);
 * listener callbacks are posted to the injected [SensorHandler] thread, off the main looper (#71).
 */
@Singleton
class ShakeDetector @Inject constructor(
    private val sensorManager: SensorManager,
    @SensorHandler private val sensorHandler: Handler,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /**
     * Hot, shared [Flow] of accelerometer readings at [SensorManager.SENSOR_DELAY_GAME] rate.
     *
     * [SharingStarted.WhileSubscribed] unregisters the physical sensor once the last collector
     * leaves, after a short grace timeout to avoid churn across screen transitions.
     */
    private val shared: SharedFlow<AccelerometerSample> = cold().shareIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = SENSOR_STOP_TIMEOUT_MILLIS),
        replay = 1,
    )

    /** Returns the shared accelerometer stream. */
    fun samples(): Flow<AccelerometerSample> = shared

    private fun cold(): Flow<AccelerometerSample> = callbackFlow {
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

        sensorManager.registerListener(listener, accelSensor, SensorManager.SENSOR_DELAY_GAME, sensorHandler)
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    companion object {
        /** Grace period before the physical sensor is unregistered after the last collector leaves. */
        private const val SENSOR_STOP_TIMEOUT_MILLIS = 2_000L
    }
}
