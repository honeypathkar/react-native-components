package com.honeypathkar.soundboxengine.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.i("SoundboxBootReceiver", "Device boot received; reviving soundbox service")
        SoundboxForegroundService.ensureRunning(context)
    }
}
