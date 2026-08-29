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

  /**
   * Scheduled work lives on the class, not the instance.
   *
   * This manager is built wherever it is needed — the bridge module holds one,
   * every broadcast receiver constructs its own, and the docs invite an FCM
   * service to do the same. That is what lets it work without React Native.
   * But it means "the manager" is never one object, so a timer parked in an
   * instance field is invisible to every other instance: a receiver handling a
   * Cancel cannot stop a ticker the bridge started, and it would go on firing
   * against an activity that no longer exists.
   */
  private val handler get() = sharedHandler

  /** Raised for outcomes JS should be able to branch on. */
  class LiveUpdateException(val code: String, message: String) : Exception(message)

  /**
   * Scheduled work lives on the class, not the instance.
   *
   * This manager is built wherever it is needed — the bridge module holds one,
   * every broadcast receiver constructs its own, and the docs invite an FCM
   * service to do the same. That is the point of it having no React Native
   * dependency. But it means "the manager" is never one object, so a timer
   * parked in an instance field is invisible to every other instance: a
   * receiver handling a Cancel could not stop a ticker the bridge started, and
   * it would keep firing against an activity that no longer exists.
   */


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


    scheduleAutoProgress(id)
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
    // update() replaces content wholesale, so the schedule is rebuilt from the
    // new span rather than kept from the old one.
    scheduleAutoProgress(id)
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
    cancelAutoProgress(id)

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
    // A re-posted update needs its ticker back; the swipe stopped nothing else.
    scheduleAutoProgress(id)
  }

  fun endAll() {
    store.all().forEach { entry ->
      cancelLinger(entry.id)
      cancelAutoProgress(entry.id)
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

  // ─── Auto progress ─────────────────────────────────────────────────────────

  /**
   * Redraw a clock-driven track on a schedule of our own.
   *
   * Android has no time-based progress track — `ProgressStyle` holds a number,
   * not a span — so something has to re-post the notification for the bar to
   * move. Doing it here rather than from JS matters more than it looks: React
   * Native stops every setTimeout and setInterval the moment the activity
   * pauses, so a JS-driven bar freezes exactly when the user goes to look at
   * it. A native Handler is not on that leash.
   *
   * It is still bound by the process: once Android freezes a cached app
   * nothing runs, here or anywhere. Each tick recomputes from the clock rather
   * than stepping a counter, so a frozen stretch costs the frames but not the
   * position — the bar is correct again on the first tick after the thaw
   * rather than resuming wherever it left off. For a bar that keeps moving
   * while the process is frozen, the app needs a foreground service; nothing
   * a notification library can do reaches that far.
   */
  private fun scheduleAutoProgress(id: String) {
    cancelAutoProgress(id)

    val entry = store.get(id) ?: return
    val content = entry.content
    if (!content.autoProgress || !content.progressBar) return

    val start = content.startsAt ?: return
    val end = content.endsAt ?: return
    if (end <= start || System.currentTimeMillis() >= end) return

    val interval = autoProgressIntervalMs(end - start)
    val tick = object : Runnable {
      override fun run() {
        val current = store.get(id)
        if (current == null) {
          ticking.remove(id)
          return
        }
        post(
          current.notificationId,
          builder.build(
            channels.channelId,
            id,
            current.name,
            current.content,
            current.notificationId,
            persistent = current.persistent,
          ),
        )
        if (System.currentTimeMillis() < end) {
          handler.postDelayed(this, interval)
        } else {
          ticking.remove(id)
        }
      }
    }

    ticking[id] = tick
    handler.postDelayed(tick, interval)
  }

  /**
   * One step per half a percent of the run, held between a second and half a
   * minute. A minute-long timer moves every second; an hour-long one every
   * eighteen, because a track a thousand units wide cannot show finer than
   * that anyway and every redraw is a notification post.
   */
  private fun autoProgressIntervalMs(spanMs: Long): Long =
    (spanMs / 200).coerceIn(1_000L, 30_000L)

  private fun cancelAutoProgress(id: String) {
    ticking.remove(id)?.let(handler::removeCallbacks)
  }

  private fun cancelLinger(id: String) {
    lingering.remove(id)?.let(handler::removeCallbacks)
  }

  companion object {
    private const val TAG = "LiveUpdate"
    private const val LIVE_UPDATE_SDK = 36

    private val sharedHandler = Handler(Looper.getMainLooper())

    /** Pending linger cancellations, for API levels without a system timer. */
    private val lingering = mutableMapOf<String, Runnable>()

    /** Auto-progress tickers, one per live update that asked for one. */
    private val ticking = mutableMapOf<String, Runnable>()
  }
}
