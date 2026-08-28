package com.stepcounter

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.util.concurrent.TimeUnit

class StepCounterModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), SensorEventListener {

    private val storage = StepStorage(reactContext)
    private val permissionManager = PermissionManager(reactContext)
    private var sensorManager: SensorManager? = null
    private var stepSensor: Sensor? = null
    private var isListening = false
    private var currentRawSensorValue: Long = 0L

    override fun getName(): String {
        return "StepCounter"
    }

    init {
        sensorManager = reactContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        scheduleBackgroundWorker()
    }

    private fun scheduleBackgroundWorker() {
        try {
            val syncWorkRequest = PeriodicWorkRequestBuilder<StepSyncWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(reactContext).enqueueUniquePeriodicWork(
                "StepSyncWorker",
                ExistingPeriodicWorkPolicy.KEEP,
                syncWorkRequest
            )
        } catch (e: Exception) {
            // Background worker schedule fallback
        }
    }

    @ReactMethod
    fun isSupported(promise: Promise) {
        promise.resolve(stepSensor != null)
    }

    @ReactMethod
    fun start(promise: Promise) {
        if (!permissionManager.hasActivityRecognitionPermission()) {
            promise.reject("PERMISSION_DENIED", "ACTIVITY_RECOGNITION permission is required")
            return
        }

        if (stepSensor == null) {
            promise.reject("SENSOR_NOT_AVAILABLE", "Step counter sensor not available on this device")
            return
        }

        if (!isListening) {
            val success = sensorManager?.registerListener(
                this,
                stepSensor,
                SensorManager.SENSOR_DELAY_UI
            ) ?: false
            isListening = success
            promise.resolve(success)
        } else {
            promise.resolve(true)
        }
    }

    @ReactMethod
    fun stop(promise: Promise) {
        if (isListening) {
            sensorManager?.unregisterListener(this)
            isListening = false
        }
        promise.resolve(true)
    }

    @ReactMethod
    fun getTodaySteps(promise: Promise) {
        val lastRaw = if (currentRawSensorValue > 0) currentRawSensorValue else storage.lastSensorValue
        val todaySteps = storage.calculateTodaySteps(lastRaw)
        promise.resolve(todaySteps.toDouble())
    }

    @ReactMethod
    fun getCurrentSensorValue(promise: Promise) {
        val lastRaw = if (currentRawSensorValue > 0) currentRawSensorValue else storage.lastSensorValue
        promise.resolve(lastRaw.toDouble())
    }

    @ReactMethod
    fun resetToday(promise: Promise) {
        val lastRaw = if (currentRawSensorValue > 0) currentRawSensorValue else storage.lastSensorValue
        storage.resetToday(lastRaw)
        sendStepEvent(0L, lastRaw)
        promise.resolve(true)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event != null && event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            val rawSensorValue = event.values[0].toLong()
            currentRawSensorValue = rawSensorValue

            val todaySteps = storage.calculateTodaySteps(rawSensorValue)
            sendStepEvent(todaySteps, rawSensorValue)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun sendStepEvent(todaySteps: Long, rawValue: Long) {
        if (reactContext.hasActiveCatalystInstance()) {
            val map = Arguments.createMap()
            map.putDouble("today", todaySteps.toDouble())
            map.putDouble("rawSensorValue", rawValue.toDouble())

            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("StepCounterUpdate", map)
        }
    }

    @ReactMethod
    fun addListener(eventName: String) {}

    @ReactMethod
    fun removeListeners(count: Int) {}
}
