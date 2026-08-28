package com.stepcounter

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StepStorage(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "step_counter_prefs"
        private const val KEY_BASELINE = "baseline_value"
        private const val KEY_BASELINE_DATE = "baseline_date"
        private const val KEY_LAST_SENSOR_VALUE = "last_sensor_value"
        private const val KEY_ACCUMULATED_PREV_BOOT = "accumulated_prev_boot"

        fun getTodayDateString(): String {
            val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            return formatter.format(Date())
        }
    }

    var baseline: Long
        get() = prefs.getLong(KEY_BASELINE, -1L)
        set(value) = prefs.edit().putLong(KEY_BASELINE, value).apply()

    var baselineDate: String
        get() = prefs.getString(KEY_BASELINE_DATE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BASELINE_DATE, value).apply()

    var lastSensorValue: Long
        get() = prefs.getLong(KEY_LAST_SENSOR_VALUE, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SENSOR_VALUE, value).apply()

    var accumulatedPrevBoot: Long
        get() = prefs.getLong(KEY_ACCUMULATED_PREV_BOOT, 0L)
        set(value) = prefs.edit().putLong(KEY_ACCUMULATED_PREV_BOOT, value).apply()

    fun updateBaselineForToday(currentSensorValue: Long) {
        baseline = currentSensorValue
        baselineDate = getTodayDateString()
        lastSensorValue = currentSensorValue
        accumulatedPrevBoot = 0L
    }

    fun handleRebootDetected(currentSensorValue: Long) {
        val todayStr = getTodayDateString()
        if (baselineDate == todayStr && baseline != -1L && lastSensorValue >= baseline) {
            // Save steps accumulated prior to reboot
            accumulatedPrevBoot += (lastSensorValue - baseline)
        }
        baseline = currentSensorValue
        baselineDate = todayStr
        lastSensorValue = currentSensorValue
    }

    fun calculateTodaySteps(currentSensorValue: Long): Long {
        val todayStr = getTodayDateString()

        // New day or uninitialized baseline
        if (baseline == -1L || baselineDate != todayStr) {
            updateBaselineForToday(currentSensorValue)
            return 0L
        }

        // Reboot detected: current raw sensor value restarted from 0 or is lower than stored baseline
        if (currentSensorValue < baseline) {
            handleRebootDetected(currentSensorValue)
        }

        lastSensorValue = currentSensorValue
        val stepsThisBoot = currentSensorValue - baseline
        val totalSteps = accumulatedPrevBoot + (if (stepsThisBoot < 0L) 0L else stepsThisBoot)
        return if (totalSteps < 0L) 0L else totalSteps
    }

    fun resetToday(currentSensorValue: Long) {
        updateBaselineForToday(currentSensorValue)
    }
}
