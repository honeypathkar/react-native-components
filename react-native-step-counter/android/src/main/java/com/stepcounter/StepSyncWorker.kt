package com.stepcounter

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.work.Worker
import androidx.work.WorkerParameters

class StepSyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams), SensorEventListener {

    private val storage = StepStorage(context)
    private var sensorManager: SensorManager? = null

    override fun doWork(): Result {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        if (stepSensor != null) {
            sensorManager?.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
            // Wait briefly for single event in background thread
            try {
                Thread.sleep(3000)
            } catch (e: InterruptedException) {
                // Ignore
            } finally {
                sensorManager?.unregisterListener(this)
            }
        }

        return Result.success()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event != null && event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            val rawValue = event.values[0].toLong()
            storage.calculateTodaySteps(rawValue)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
