package com.liveupdate

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.liveupdate.models.LiveUpdateContent

/**
 * Everything a live update does on Android, with no React Native in sight.
 *
 * Separated from the bridge module on purpose. Reliable updates on Android
 * arrive while JS is not running — an FCM push wakes the app, and a
 * `FirebaseMessagingService` is a native class with no bridge to call into.
 * That service can construct this and call [update] directly, which is what
 * makes server-driven updates work without the package depending on Firebase,
 * or on React Native being alive.
 */
class LiveUpdateManager(context: Context) {

  private val context = context.applicationContext
  private val store = ActivityStore(this.context)
  private val channels = NotificationChannelManager(this.context)
  private val builder = ProgressNotificationBuilder(this.context)
  private val capabilities = CapabilityManager(this.context, channels)
  private val notifications get() = NotificationManagerCompat.from(context)

  /** Pending linger cancellations, for the API levels without a system timer. */
  private val lingering = mutableMapOf<String, Runnable>()
  private val handler = Handler(Looper.getMainLooper())

  /** Raised for outcomes JS should be able to branch on. */
  class LiveUpdateException(val code: String, message: String) : Exception(message)

  // ─── Lifecycle ─────────────────────────────────────────────────────────────

  /**
   * Post a live update, or replace one already running under this id.
   *
   * Reusing an id updates rather than stacking a second notification. A screen
   * that re-mounts and calls `start()` again is the common case, and two
   * identical deliveries on the lock screen is never what was meant.
   */
  fun start(
    id: String,
    name: String,
    content: LiveUpdateContent,
    /** Put it back if the user swipes it away. See StartOptions.persistent. */
    persistent: Boolean = false,
  ) {
    requirePostable()
    channels.ensure()

    cancelLinger(id)
    val existing = store.get(id)
    val notificationId = existing?.notificationId ?: store.nextNotificationId()
    val now = System.currentTimeMillis()

    post(
      notificationId,
      builder.build(channels.channelId, id, name, content, notificationId, persistent = persistent),
    )
    store.put(
      ActivityStore.Entry(
        id = id,
        name = name,
        notificationId = notificationId,
        content = content,
        persistent = persistent,
        createdAt = existing?.createdAt ?: now,
        updatedAt = now,
      ),
    )
  }

  /**
   * Change what a running live update shows.
   *
   * The name comes from the store rather than the caller: it is static for the
   * life of the activity, and asking for it again on every update is how it
   * ends up drifting.
   */
  fun update(id: String, content: LiveUpdateContent) {
    requirePostable()
    val existing = store.get(id)
      ?: throw LiveUpdateException("NOT_FOUND", "No live update with id $id")

    channels.ensure()
    cancelLinger(id)
    post(
      existing.notificationId,
      builder.build(
        channels.channelId,
        id,
        existing.name,
        content,
        existing.notificationId,
        persistent = existing.persistent,
      ),
    )
    store.put(existing.copy(content = content, updatedAt = System.currentTimeMillis()))
  }

  /**
   * Finish it, optionally leaving the final state up for a moment.
   *
   * Ending something that is not running is not an error — it is the outcome
   * the caller asked for, and treating it as a failure means every teardown
   * path needs a try/catch for a race it cannot avoid.
   */
  fun end(id: String, dismissAfterMs: Long) {
    val entry = store.remove(id) ?: return
    cancelLinger(id)

    if (dismissAfterMs <= 0) {
      notifications.cancel(entry.notificationId)
      return
    }

    // Kept ongoing through the linger so the final state — "Delivered" — is
    // shown on the promoted surface rather than dropping out of the chip the
    // instant it matters most. `setTimeoutAfter` guarantees it still goes away
    // if this process does not survive the wait.
    post(
      entry.notificationId,
      builder.build(
        channels.channelId,
        id,
        entry.name,
        entry.content,
        entry.notificationId,
        // Not persistent through the linger: the run is over, and a user who
        // swipes away a "Delivered" card has finished with it.
        persistent = false,
        timeoutAfterMs = dismissAfterMs,
      ),
    )

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      // setTimeoutAfter does nothing here, so fall back to a delayed cancel
      // and accept that a process death during the window leaks it.
      val cancel = Runnable {
        notifications.cancel(entry.notificationId)
        lingering.remove(id)
      }
      lingering[id] = cancel
      handler.postDelayed(cancel, dismissAfterMs)
    }
  }

  /**
   * Post a dismissed live update again. Called only by [DismissReceiver].
   *
   * Reads the store rather than trusting the broadcast, which makes the
   * teardown race safe by construction: `end()` removes the entry before it
   * touches the notification, so a dismissal that arrives around the same time
   * finds nothing and does nothing.
   */
  fun restore(id: String) {
    val entry = store.get(id) ?: return
    if (!entry.persistent) return
    if (!notifications.areNotificationsEnabled()) return

    channels.ensure()
    post(
      entry.notificationId,
      builder.build(
        channels.channelId,
        id,
        entry.name,
        entry.content,
        entry.notificationId,
        persistent = true,
      ),
    )
  }

  fun endAll() {
    store.all().forEach { entry ->
      cancelLinger(entry.id)
      notifications.cancel(entry.notificationId)
    }
    store.clear()
  }

  /**
   * Ids currently on screen.
   *
   * Reconciled against the system's own list rather than trusted from the
   * store, and the difference is pruned. A reboot clears every notification
   * while leaving our records intact, so the store alone would report
   * deliveries that vanished overnight — and iOS, which reads ActivityKit's
   * live list, would disagree with Android about the same app state.
   */
  fun running(): List<String> {
    val stored = store.all()
    if (stored.isEmpty()) return emptyList()

    val live = activeNotificationIds() ?: return stored.map { it.id }

    val (present, gone) = stored.partition { it.notificationId in live }
    gone.forEach { store.remove(it.id) }
    return present.map { it.id }
  }

  /** Null when the platform will not tell us, which is not the same as empty. */
  private fun activeNotificationIds(): Set<Int>? =
    try {
      notifications.activeNotifications.map { it.id }.toSet()
    } catch (e: Exception) {
      // Some OEM builds throw from the underlying binder call. Falling back to
      // the store's view is better than reporting nothing is running.
      Log.w(TAG, "could not read active notifications; trusting the store", e)
      null
    }

  // ─── Queries and configuration ─────────────────────────────────────────────

  fun support() = capabilities.support()

  fun capabilities() = capabilities.capabilities()

  fun configureChannel(config: com.facebook.react.bridge.ReadableMap) =
    channels.configure(config)

  // ─── Internals ─────────────────────────────────────────────────────────────

  private fun post(notificationId: Int, notification: android.app.Notification) {
    try {
      notifications.notify(notificationId, notification)
    } catch (e: SecurityException) {
      // Raised on some versions when POST_NOTIFICATIONS is missing; on others
      // notify() simply does nothing. requirePostable() catches the common
      // case first so this is the backstop, not the check.
      throw LiveUpdateException("PERMISSION_DENIED", "Notification permission is not granted")
    }

    if (Build.VERSION.SDK_INT >= LIVE_UPDATE_SDK) {
      // The system's own verdict on whether this notification qualifies for
      // promotion, logged because it is the only way to tell "we built it
      // wrong" from "this device is not promoting it". Nothing reports the
      // latter, so knowing the former is true narrows it to one answer.
      Log.d(
        TAG,
        "posted $notificationId promotable=" +
          NotificationCompat.hasPromotableCharacteristics(notification),
      )
    }
  }

  /**
   * Fail loudly when nothing could appear.
   *
   * `notify()` with notifications switched off returns without complaint, so
   * the app would believe it had posted a delivery that no user will ever see.
   */
  private fun requirePostable() {
    if (!notifications.areNotificationsEnabled()) {
      throw LiveUpdateException(
        "PERMISSION_DENIED",
        "Notifications are turned off for this app",
      )
    }
    if (channels.isChannelBlocked()) {
      throw LiveUpdateException(
        "PERMISSION_DENIED",
        "The live update notification channel is turned off",
      )
    }
  }

  private fun cancelLinger(id: String) {
    lingering.remove(id)?.let(handler::removeCallbacks)
  }

  companion object {
    private const val TAG = "LiveUpdate"
    private const val LIVE_UPDATE_SDK = 36
  }
}
