package com.stepcounter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val storage = StepStorage(context)
            // On reboot, preserve steps walked earlier today before resetting baseline
            storage.handleRebootDetected(0L)
        }
    }
}
