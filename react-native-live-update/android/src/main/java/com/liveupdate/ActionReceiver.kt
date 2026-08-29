package com.liveupdate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Finishes a live update from its own button, without opening the app.
 *
 * The default for an action is to launch the app at a deep link, because that
 * is the only thing a notification button can reliably do when the JS runtime
 * may not be running — and on a backgrounded app it usually is not. But
 * "Cancel" on a timer does not want the app: bringing a workout screen to the
 * front to dismiss the very notification the user just dismissed is the
 * opposite of what they asked for.
 *
 * A broadcast receiver has no such constraint. It is native, it runs whether
 * or not React Native is alive, and the manager it calls into was already
 * written to work without a bridge. So the tap ends the activity here and the
 * user stays where they are.
 *
 * The app finds out the next time it looks: the activity is gone from the
 * store, so `getRunning()` no longer reports it. Nothing is pushed at JS,
 * because there may be no JS to push to.
 */
class ActionReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val id = intent.getStringExtra(EXTRA_ID) ?: return
    try {
      // Immediate, not lingering: the user asked for it gone.
      LiveUpdateManager(context).end(id, 0L)
    } catch (e: Exception) {
      // This runs on the app's main thread from a system broadcast; throwing
      // here would take the process down for a button press.
      Log.w(TAG, "could not end live update $id from its action", e)
    }
  }

  companion object {
    const val ACTION = "com.liveupdate.ACTION_END"
    const val EXTRA_ID = "com.liveupdate.EXTRA_ID"
    const val EXTRA_ACTION_ID = "com.liveupdate.EXTRA_ACTION_ID"
    private const val TAG = "LiveUpdate"
  }
}
