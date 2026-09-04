package com.honeypathkar.soundboxengine.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

class SoundboxForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        SoundboxNotification.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServiceWatchdog.cancel(this)
            stoppedByUser = true
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE)
        val message = intent?.getStringExtra(EXTRA_MESSAGE)

        startForegroundCompat(title, message)
        isRunning = true
        ServiceWatchdog.schedule(this)

        return START_STICKY
    }

    private fun startForegroundCompat(title: String?, message: String?) {
        val notification = SoundboxNotification.build(this, title, message)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    SoundboxNotification.NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(SoundboxNotification.NOTIFICATION_ID, notification)
            }
            Log.i(TAG, "Foreground service started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        ServiceWatchdog.scheduleSoon(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        if (!stoppedByUser) {
            ServiceWatchdog.scheduleSoon(this)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "SoundboxFGService"
        const val ACTION_STOP = "com.honeypathkar.soundboxengine.STOP"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MESSAGE = "extra_message"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var stoppedByUser: Boolean = false

        fun start(context: Context, title: String? = null, message: String? = null) {
            stoppedByUser = false
            val intent = Intent(context, SoundboxForegroundService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MESSAGE, message)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not start foreground service", e)
            }
        }

        fun stop(context: Context) {
            stoppedByUser = true
            ServiceWatchdog.cancel(context)
            try {
                context.stopService(Intent(context, SoundboxForegroundService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Could not stop foreground service", e)
            }
        }

        fun ensureRunning(context: Context) {
            if (isRunning) {
                SoundboxNotification.repost(context)
                ServiceWatchdog.schedule(context)
            } else {
                start(context)
            }
        }
    }
}
