package com.liveupdate

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

/**
 * What this device will and will not do, reported honestly.
 *
 * The hard part is what this file deliberately does not do. There is no
 * manufacturer table here, and no `isSamsung()`. Samsung's Now Bar, Realme's
 * Live Alerts and the rest are OEM shells over the same Android 16 promoted-
 * ongoing notification: none publishes a third-party SDK, a version contract,
 * or a way to ask whether it will display anything. Mapping "manufacturer ==
 * Samsung" to "the Now Bar will show this" would be a guess with an API's
 * authority, and it would be wrong on every One UI build that predates the
 * feature or has it switched off.
 *
 * So the package reports the generic Android capability, hands over the raw
 * `manufacturer`/`model` for the app's own telemetry, and stops there.
 */
internal class CapabilityManager(
  private val context: Context,
  private val channels: NotificationChannelManager,
) {

  private val compat get() = NotificationManagerCompat.from(context)

  fun capabilities(): WritableMap {
    val notificationsEnabled = compat.areNotificationsEnabled()
    val channelBlocked = channels.isChannelBlocked()
    val enabled = notificationsEnabled && !channelBlocked
    val promoted = canPostPromoted()

    return Arguments.createMap().apply {
      putString("platform", "android")
      putString("osVersion", Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString())
      // Always true: even the oldest supported device can show an ongoing
      // notification with a progress bar. What varies is the prominence, and
      // that is what the fields below are for.
      putBoolean("supported", true)
      putBoolean("enabled", enabled)
      // Below API 36 the compat library folds the segmented track down to a
      // single plain bar. The call still works; the stages just do not draw.
      putBoolean("stagesSupported", Build.VERSION.SDK_INT >= LIVE_UPDATE_SDK)
      putBoolean("lockScreenSupported", true)
      // Android has no per-activity push channel. A data push wakes the app,
      // which then calls update() — see the README.
      putBoolean("pushUpdatesSupported", false)
      putMap(
        "promotedSurface",
        Arguments.createMap().apply {
          putBoolean("available", promoted)
          if (promoted) putString("type", "statusBarChip")
        },
      )
      putString("notificationPermission", permissionStatus())
      putMap(
        "device",
        Arguments.createMap().apply {
          putString("manufacturer", Build.MANUFACTURER ?: "unknown")
          putString("model", Build.MODEL ?: "unknown")
        },
      )
      degradedReason(notificationsEnabled, channelBlocked, promoted)?.let {
        putString("reason", it)
      }
    }
  }

  /** The two booleans the JS `isSupported()` shorthand reports. */
  fun support(): WritableMap =
    Arguments.createMap().apply {
      val notificationsEnabled = compat.areNotificationsEnabled()
      val channelBlocked = channels.isChannelBlocked()
      putBoolean("supported", true)
      putBoolean("enabled", notificationsEnabled && !channelBlocked)
      degradedReason(notificationsEnabled, channelBlocked, canPostPromoted())?.let {
        putString("reason", it)
      }
    }

  /**
   * Whether the system will entertain a promotion request from this app.
   *
   * Backed by the `POST_PROMOTED_NOTIFICATIONS` app op, which the user can
   * revoke per app. Revoked, the system declines the promotion silently and
   * leaves an ordinary notification — no exception, nothing in the log. That
   * invisibility is the whole reason this is surfaced.
   */
  private fun canPostPromoted(): Boolean {
    if (Build.VERSION.SDK_INT < LIVE_UPDATE_SDK) return false
    return try {
      compat.canPostPromotedNotifications()
    } catch (_: Throwable) {
      // A device reporting API 36 without the API behind it. Assume yes and
      // let the notification post either way — degrading to a plain one is a
      // fine outcome; refusing to post is not.
      true
    }
  }

  private fun permissionStatus(): String =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      // No runtime permission before 13. Notifications can still be switched
      // off in settings, which is `enabled`, not this.
      "granted"
    } else if (
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED
    ) {
      "granted"
    } else {
      "denied"
    }

  /** Diagnostic, in the order the developer should act on it. */
  private fun degradedReason(
    notificationsEnabled: Boolean,
    channelBlocked: Boolean,
    promoted: Boolean,
  ): String? = when {
    !notificationsEnabled ->
      "Notifications are turned off for this app"
    channelBlocked ->
      "The live update notification channel is turned off (Settings > Notifications)"
    Build.VERSION.SDK_INT < LIVE_UPDATE_SDK ->
      "Android 16 is required for the promoted status-bar chip; " +
        "this shows as an ongoing notification instead"
    !promoted ->
      "Promoted notifications are turned off for this app (Settings > Notifications)"
    else -> null
  }

  private companion object {
    /** API 36 — Android 16, where Live Updates arrive. */
    const val LIVE_UPDATE_SDK = 36
  }
}
