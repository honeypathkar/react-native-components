package com.stepcounter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MidnightReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_DATE_CHANGED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED) {
            
            val storage = StepStorage(context)
            val lastValue = storage.lastSensorValue
            if (lastValue > 0) {
                storage.updateBaselineForToday(lastValue)
            }
        }
    }
}
