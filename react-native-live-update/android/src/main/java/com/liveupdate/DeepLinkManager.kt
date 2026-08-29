package com.liveupdate

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/** Where tapping a live update takes the user. */
internal object DeepLinkManager {

  /**
   * A tap target for one notification.
   *
   * Falls back to the app's launcher intent when there is no deep link, or
   * when the link resolves to nothing — a notification that swallows taps
   * reads as a frozen app, and landing on the home screen is a far better
   * failure than landing nowhere.
   */
  fun contentIntent(
    context: Context,
    deepLink: String?,
    /**
     * Must differ per live update. PendingIntents are matched on requestCode
     * plus the intent's fields, ignoring extras — reuse one and two orders
     * share a single intent, so both notifications open whichever was posted
     * last.
     */
    requestCode: Int,
  ): PendingIntent? {
    val intent = deepLinkIntent(context, deepLink) ?: launchIntent(context) ?: return null
    return PendingIntent.getActivity(
      context,
      requestCode,
      intent,
      // IMMUTABLE is required from API 31 and correct everywhere: nothing
      // downstream needs to rewrite this intent, and a mutable one handed to
      // the system is an intent any app holding it could redirect.
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  /**
   * Fired when the user swipes the notification away.
   *
   * A broadcast rather than a service: this has to arrive even when nothing of
   * the app is running, and it does a few milliseconds of work. The request
   * code is the notification id for the same reason the content intent uses
   * it — PendingIntents match on request code and intent fields, so a shared
   * one would report every dismissal as the same activity.
   */
  fun dismissIntent(context: Context, id: String, requestCode: Int): PendingIntent {
    val intent = Intent(context, DismissReceiver::class.java).apply {
      action = DismissReceiver.ACTION
      // Explicit component plus package: a dismissal is internal business and
      // has no reason to be visible to, or receivable by, anything else.
      setPackage(context.packageName)
      putExtra(DismissReceiver.EXTRA_ID, id)
    }
    return PendingIntent.getBroadcast(
      context,
      requestCode,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  /**
   * A tap target that is handled in-process instead of by an activity.
   *
   * Same PendingIntent matching rules as everything else here — request codes
   * have to differ per button, since extras are not part of the match and two
   * buttons sharing one would end whichever activity was posted last.
   */
  fun endIntent(
    context: Context,
    id: String,
    actionId: String,
    requestCode: Int,
  ): PendingIntent {
    val intent = Intent(context, ActionReceiver::class.java).apply {
      action = ActionReceiver.ACTION
      setPackage(context.packageName)
      putExtra(ActionReceiver.EXTRA_ID, id)
      putExtra(ActionReceiver.EXTRA_ACTION_ID, actionId)
    }
    return PendingIntent.getBroadcast(
      context,
      requestCode,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun deepLinkIntent(context: Context, deepLink: String?): Intent? {
    if (deepLink.isNullOrBlank()) return null
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
      // Keep it inside the app. Without this the URL goes through normal
      // resolution, where any app that has registered the same scheme can
      // receive it — and the user taps your delivery to open somebody else's
      // app.
      setPackage(context.packageName)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    if (intent.resolveActivity(context.packageManager) == null) {
      // The app has not declared an intent-filter for this scheme. Silently
      // dead taps are near-impossible to diagnose from JS, so say it once.
      Log.w(
        "LiveUpdate",
        "deepLink \"$deepLink\" matches no activity in ${context.packageName}; " +
          "add an <intent-filter> for its scheme. Opening the app instead.",
      )
      return null
    }
    return intent
  }

  private fun launchIntent(context: Context): Intent? =
    context.packageManager
      .getLaunchIntentForPackage(context.packageName)
      ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
}
