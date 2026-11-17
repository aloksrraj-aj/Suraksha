package com.example.data.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

enum class CompassReliability(val label: String, val isUsable: Boolean) {
    RELIABLE_HIGH("Compass calibrated (High)", true),
    RELIABLE_MEDIUM("Compass active (Medium)", true),
    UNRELIABLE_LOW("Compass calibration low — use North-Up", false),
    NO_COMPASS_HARDWARE("Compass sensor unavailable — North-Up only", false),
}

data class HeadingState(
    val azimuthDegrees: Double = 0.0,
    val reliability: CompassReliability = CompassReliability.NO_COMPASS_HARDWARE,
    val updatedAtMillis: Long = 0L,
)

/**
 * Real Android SensorManager heading provider using TYPE_ROTATION_VECTOR (falling back to
 * TYPE_ACCELEROMETER + TYPE_MAGNETIC_FIELD). Never synthesizes compass headings when hardware is missing.
 */
class CompassHeadingProvider(context: Context) : SensorEventListener {
    private val sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val _headingState = MutableStateFlow(
        HeadingState(
            azimuthDegrees = 0.0,
            reliability = if (rotationSensor != null || (accelSensor != null && magnetSensor != null)) {
                CompassReliability.UNRELIABLE_LOW
            } else {
                CompassReliability.NO_COMPASS_HARDWARE
            },
        )
    )
    val headingState: StateFlow<HeadingState> = _headingState.asStateFlow()

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val lastAccel = FloatArray(3)
    private val lastMag = FloatArray(3)
    private var hasAccel = false
    private var hasMag = false
    private var smoothedDeg: Double? = null
    private var currentAccuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE

    fun start() {
        val sm = sensorManager ?: return
        if (rotationSensor != null) {
            sm.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        } else if (accelSensor != null && magnetSensor != null) {
            sm.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_UI)
            sm.registerListener(this, magnetSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            _headingState.value = HeadingState(0.0, CompassReliability.NO_COMPASS_HARDWARE, System.currentTimeMillis())
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                emitAzimuthRad(orientationAngles[0])
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, lastAccel, 0, 3)
                hasAccel = true
                computeFallbackOrientation()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, lastMag, 0, 3)
                hasMag = true
                computeFallbackOrientation()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        currentAccuracy = accuracy
        val cur = _headingState.value
        _headingState.value = cur.copy(reliability = mapAccuracy(accuracy))
    }

    private fun computeFallbackOrientation() {
        if (!hasAccel || !hasMag) return
        if (SensorManager.getRotationMatrix(rotationMatrix, null, lastAccel, lastMag)) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            emitAzimuthRad(orientationAngles[0])
        }
    }

    private fun emitAzimuthRad(azimuthRad: Float) {
        val rawDeg = ((Math.toDegrees(azimuthRad.toDouble()) + 360.0) % 360.0)
        val filtered = smoothAngleDegrees(smoothedDeg, rawDeg, alpha = 0.25)
        smoothedDeg = filtered
        _headingState.value = HeadingState(
            azimuthDegrees = (filtered * 10.0).roundToInt() / 10.0,
            reliability = mapAccuracy(currentAccuracy),
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    companion object {
        fun smoothAngleDegrees(prev: Double?, next: Double, alpha: Double = 0.25): Double {
            if (prev == null) return ((next % 360.0) + 360.0) % 360.0
            var diff = next - prev
            while (diff < -180.0) diff += 360.0
            while (diff > 180.0) diff -= 360.0
            return (((prev + alpha * diff) % 360.0) + 360.0) % 360.0
        }

        private fun mapAccuracy(accuracy: Int): CompassReliability = when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> CompassReliability.RELIABLE_HIGH
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> CompassReliability.RELIABLE_MEDIUM
            else -> CompassReliability.UNRELIABLE_LOW
        }
    }
}
