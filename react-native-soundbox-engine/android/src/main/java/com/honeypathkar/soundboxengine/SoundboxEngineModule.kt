package com.honeypathkar.soundboxengine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.facebook.react.bridge.*
import com.honeypathkar.soundboxengine.service.SoundboxForegroundService

class SoundboxEngineModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "RNSoundboxEngine"

    @ReactMethod
    fun startService(options: ReadableMap, promise: Promise) {
        val title = if (options.hasKey("notificationTitle")) options.getString("notificationTitle") else null
        val message = if (options.hasKey("notificationMessage")) options.getString("notificationMessage") else null

        SoundboxForegroundService.start(reactContext, title, message)
        promise.resolve(true)
    }

    @ReactMethod
    fun stopService(promise: Promise) {
        SoundboxForegroundService.stop(reactContext)
        promise.resolve(true)
    }

    @ReactMethod
    fun isServiceRunning(promise: Promise) {
        promise.resolve(SoundboxForegroundService.isRunning)
    }

    @ReactMethod
    fun isIgnoringBatteryOptimizations(promise: Promise) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = reactContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isIgnoring = pm?.isIgnoringBatteryOptimizations(reactContext.packageName) ?: false
            promise.resolve(isIgnoring)
        } else {
            promise.resolve(true)
        }
    }

    @ReactMethod
    fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${reactContext.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                reactContext.startActivity(intent)
            } catch (e: Exception) {
                // Fallback to generic battery optimization settings
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                reactContext.startActivity(fallbackIntent)
            }
        }
    }
}
