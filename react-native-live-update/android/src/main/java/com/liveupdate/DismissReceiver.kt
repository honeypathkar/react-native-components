package com.liveupdate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Puts a live update back after the user swipes it away.
 *
 * Only reached for activities started with `persistent: true`, and only on a
 * *user* dismissal — a `deleteIntent` does not fire when the app cancels a
 * notification itself, which is what keeps `end()` from fighting its own
 * teardown.
 *
 * The store is the safety catch. `end()` and `endAll()` remove their entries
 * before touching the notification, so by the time any stray delete intent
 * arrives there is nothing to restore and this quietly does nothing. A
 * delivery that finished stays finished.
 */
class DismissReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val id = intent.getStringExtra(EXTRA_ID) ?: return
    try {
      LiveUpdateManager(context).restore(id)
    } catch (e: Exception) {
      // A restore that throws must not take the app's process with it — this
      // runs in the app's main thread on a system broadcast.
      Log.w(TAG, "could not restore live update $id after dismissal", e)
    }
  }

  companion object {
    const val ACTION = "com.liveupdate.ACTION_DISMISSED"
    const val EXTRA_ID = "com.liveupdate.EXTRA_ID"
    private const val TAG = "LiveUpdate"
  }
}
