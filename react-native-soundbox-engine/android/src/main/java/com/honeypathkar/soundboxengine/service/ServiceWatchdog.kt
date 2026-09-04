package com.honeypathkar.soundboxengine.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

class ServiceWatchdog : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "Watchdog alarm fired; verifying service status")
        SoundboxForegroundService.ensureRunning(context)
        schedule(context)
    }

    companion object {
        private const val TAG = "SoundboxWatchdog"
        private const val REQUEST_CODE = 4202
        private const val INTERVAL_MS = 15 * 60 * 1000L // 15 mins
        private const val SOON_DELAY_MS = 10 * 1000L    // 10 secs

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, ServiceWatchdog::class.java).apply {
                setPackage(context.packageName)
            }
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent(context),
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent(context),
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule watchdog alarm: ${e.message}")
            }
        }

        fun scheduleSoon(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val triggerAt = SystemClock.elapsedRealtime() + SOON_DELAY_MS
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent(context),
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent(context),
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule revival: ${e.message}")
            }
        }

        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            try {
                alarmManager.cancel(pendingIntent(context))
            } catch (e: Exception) {
                // Ignored
            }
        }
    }
}
