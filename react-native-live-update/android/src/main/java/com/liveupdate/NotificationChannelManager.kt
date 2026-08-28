package com.liveupdate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.facebook.react.bridge.ReadableMap

/**
 * The single channel every live update posts to.
 *
 * One channel, not one per activity: channels are a user-facing control
 * surface, and an app that mints a channel per order gives the user a settings
 * screen they cannot use to turn anything off.
 */
internal class NotificationChannelManager(private val context: Context) {

  private val prefs =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  val channelId: String
    get() = prefs.getString(KEY_ID, DEFAULT_ID) ?: DEFAULT_ID

  /**
   * Remember what the app asked for. Applied at the next `ensure()`.
   *
   * Only the *creation* of a channel reads importance — Android freezes it on
   * first create and ignores every later change, because the user owns it from
   * then on. So this can rename an existing channel but cannot re-rank it.
   */
  fun configure(config: ReadableMap) {
    prefs.edit().apply {
      config.string("channelId")?.let { putString(KEY_ID, it) }
      config.string("channelName")?.let { putString(KEY_NAME, it) }
      config.string("channelDescription")?.let { putString(KEY_DESCRIPTION, it) }
      config.string("importance")?.let { putString(KEY_IMPORTANCE, it) }
    }.commit()
    ensure()
  }

  fun ensure() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

    val channel = NotificationChannel(
      channelId,
      prefs.getString(KEY_NAME, DEFAULT_NAME) ?: DEFAULT_NAME,
      importance(),
    ).apply {
      description = prefs.getString(KEY_DESCRIPTION, DEFAULT_DESCRIPTION)
      // A live update is a persistent status display, not an alert. One that
      // buzzes on every progress change is the fastest way to have the whole
      // channel switched off — which costs the app the chip as well.
      setSound(null, null)
      enableVibration(false)
      setShowBadge(false)
    }

    manager.createNotificationChannel(channel)
  }

  /**
   * `IMPORTANCE_DEFAULT` unless the app asked for low.
   *
   * DEFAULT is not a style preference here. Android 16 will not promote a
   * notification from a channel below it, so `'low'` quietly trades away the
   * status-bar chip and everything built on it — which is why the JS docs say
   * so at the point of choosing.
   */
  private fun importance(): Int =
    when (prefs.getString(KEY_IMPORTANCE, null)) {
      "low" -> NotificationManager.IMPORTANCE_LOW
      else -> NotificationManager.IMPORTANCE_DEFAULT
    }

  /** Whether the user has switched this specific channel off. */
  fun isChannelBlocked(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    val channel = manager.getNotificationChannel(channelId) ?: return false
    return channel.importance == NotificationManager.IMPORTANCE_NONE
  }

  private val manager: NotificationManager
    get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  private fun ReadableMap.string(key: String): String? =
    if (hasKey(key) && !isNull(key)) getString(key) else null

  private companion object {
    const val PREFS = "com.liveupdate.channel"
    const val KEY_ID = "channelId"
    const val KEY_NAME = "channelName"
    const val KEY_DESCRIPTION = "channelDescription"
    const val KEY_IMPORTANCE = "importance"

    const val DEFAULT_ID = "live_update"
    const val DEFAULT_NAME = "Live updates"
    const val DEFAULT_DESCRIPTION = "Ongoing progress for something happening right now"
  }
}
