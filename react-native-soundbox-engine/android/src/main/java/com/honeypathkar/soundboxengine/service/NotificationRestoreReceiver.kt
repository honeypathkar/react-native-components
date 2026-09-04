package com.honeypathkar.soundboxengine.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotificationRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (SoundboxForegroundService.isRunning) {
            SoundboxNotification.repost(context)
        }
    }
}
