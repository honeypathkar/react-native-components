package com.liveupdate

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import com.liveupdate.models.LiveUpdateContent
import com.liveupdate.models.Stage
import java.io.File
import kotlin.math.roundToInt

/**
 * Turns a [LiveUpdateContent] into the notification Android 16 is willing to
 * promote.
 *
 * Everything here goes through `NotificationCompat`, not the platform builder.
 * androidx.core 1.17 covers the whole Live Update surface — `ProgressStyle`,
 * `setRequestPromotedOngoing`, `setShortCriticalText` — and picks the
 * implementation per device: on API 36+ it calls the real
 * `Notification.ProgressStyle`, and below it collapses the same track into
 * `setProgress(max, progress, indeterminate)`. That is exactly the fallback
 * this package would otherwise hand-write, so there is one code path instead of
 * two, no `SDK_INT` branches, and no `@Suppress("NewApi")`.
 *
 * ## What promotion actually requires
 *
 * `Notification.hasPromotableCharacteristics()` in the framework is the whole
 * contract, and it is an AND of all of these:
 *
 *     isRequestPromotedOngoing() && isOngoingEvent() && hasTitle()
 *         && hasPromotableStyle() && !isGroupSummary()
 *         && !containsCustomViews() && !isColorizedRequested()
 *
 * Two of them are counter-intuitive and cost real time to discover:
 *
 *  - The app has to *ask*, via `setRequestPromotedOngoing(true)`. There is no
 *    implicit promotion, however correct the rest of the notification is.
 *  - It must NOT be colorized. `setColorized(true)` actively disqualifies it —
 *    the opposite of the instinct, since colorized is what an ongoing media
 *    notification wants. Only the accent is set here.
 *
 * Get them all right and the same notification becomes the status-bar chip,
 * the lock-screen card, and the entry in Samsung's Now Bar. Getting them right
 * is all an app can do: promotion itself is the system's call.
 */
internal class ProgressNotificationBuilder(private val context: Context) {

  /**
   * Integer width of the whole track. `ProgressStyle` works in ints, so this
   * is the resolution a 0..1 fraction is quantised to — coarse enough to stay
   * readable in logs, fine enough that a 12-stage track still divides evenly.
   */
  private val trackTotal = 1000

  fun build(
    channelId: String,
    /** The caller's own activity id, needed to address a dismissal back to it. */
    id: String,
    name: String,
    content: LiveUpdateContent,
    notificationId: Int,
    /**
     * False for the final state shown while an ended update lingers. A
     * non-ongoing notification can never be promoted — `isOngoingEvent()` is
     * part of the contract above — so this also drops the promotion request
     * rather than asking for something the system has already ruled out.
     */
    ongoing: Boolean = true,
    /**
     * Ask to be told when the user swipes this away, so it can be put back.
     * Only meaningful while ongoing — a finished update has nothing to
     * restore.
     */
    persistent: Boolean = false,
    /**
     * Hand the system a deadline to cancel this notification itself.
     *
     * Used for the moment an ended update lingers on screen. The obvious
     * implementation — a delayed `cancel()` on a Handler — dies with the
     * process, and Android kills backgrounded apps freely, so a user who
     * swipes the app away mid-linger is left with an ongoing notification
     * nothing will ever remove. `setTimeoutAfter` is the system's own timer
     * and outlives us. It is a no-op below API 26; the manager keeps a Handler
     * for those.
     */
    timeoutAfterMs: Long? = null,
  ): Notification {
    val accent = content.color ?: DEFAULT_ACCENT

    val builder = NotificationCompat.Builder(context, channelId)
      .setSmallIcon(smallIcon(content))
      .setContentTitle(content.title.ifBlank { name })
      .setContentText(content.message)
      .setStyle(progressStyle(content, accent))
      .setOngoing(ongoing)
      // Every update re-posts the same id. Without this, each one re-alerts.
      .setOnlyAlertOnce(true)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setColor(accent)
      // Explicit, not defaulted: see the contract above. This one line is the
      // difference between a chip and a row in the shade.
      .setColorized(false)
      .setRequestPromotedOngoing(ongoing)
      .setContentIntent(
        DeepLinkManager.contentIntent(context, content.deepLink, notificationId),
      )

    if (persistent && ongoing) {
      builder.setDeleteIntent(DeepLinkManager.dismissIntent(context, id, notificationId))
    }

    // The chip text. Around seven characters before the system truncates it,
    // which is why the JS docs push callers to a single word.
    content.status?.takeIf { it.isNotBlank() }?.let(builder::setShortCriticalText)

    timeoutAfterMs?.let(builder::setTimeoutAfter)

    // Buttons along the bottom. Dropped from the final lingering state: the
    // run is over, and "Mark delivered" on a delivered order is a trap.
    if (ongoing) {
      content.actions.take(MAX_ACTIONS).forEachIndexed { index, action ->
        builder.addAction(
          NotificationCompat.Action.Builder(
            // No icon. Android has not drawn action icons in the shade since
            // Nougat, and a ProgressStyle notification shows text-only
            // buttons — passing one would be dead weight in the payload.
            null,
            action.title,
            DeepLinkManager.contentIntent(
              context,
              action.deepLink ?: content.deepLink,
              // Offset well past the notification's own request code so a
              // button and the notification body cannot share a PendingIntent.
              notificationId * ACTION_CODE_STRIDE + index,
            ),
          ).build(),
        )
      }
    }

    // A countdown the system ticks itself. The alternative — re-posting every
    // second to redraw a number — spends the app's whole update budget on
    // something the platform will do for free.
    content.endsAt?.takeIf { it > System.currentTimeMillis() }?.let { endsAt ->
      builder.setWhen(endsAt).setUsesChronometer(true).setChronometerCountDown(true)
    }

    return builder.build()
  }

  /**
   * The track: one segment per stage, plus a point wherever a stage asked to be
   * marked.
   *
   * Points are opt-in, and that is a visual decision rather than a technical
   * one. Android draws a `Point` as a filled square sitting on the track, which
   * is heavy beside a thin bar — and it lands exactly on the gap that already
   * separates two segments, so a point at every boundary restates what the gap
   * says and does it more loudly. Marking one real stop reads as a stop;
   * marking all of them reads as a row of boxes.
   *
   * The first stage is skipped whatever it asks for: its start is the start of
   * the track, where a marker would sit half outside the bar.
   */
  private fun progressStyle(
    content: LiveUpdateContent,
    accent: Int,
  ): NotificationCompat.ProgressStyle {
    // Left at ProgressStyle's default of styledByProgress = true, so the track
    // is drawn heavier behind the marker and lighter ahead of it, closing on
    // the end cap. That reads as distance covered against distance remaining,
    // which is the one thing a delivery bar is for.
    val style = NotificationCompat.ProgressStyle()
    val segments = segments(content.stages, accent)

    style.setProgressSegments(segments.map { it.segment })

    // A point marks where a stage *begins*, so it reads as "arriving here" —
    // which is what a stop actually is. That start offset is the previous
    // segment's far edge, hence the drop of the last boundary and the pairing
    // with the stage that follows it.
    val points = segments.dropLast(1).mapIndexedNotNull { index, boundary ->
      val next = content.stages.getOrNull(index + 1) ?: return@mapIndexedNotNull null
      if (!next.milestone) return@mapIndexedNotNull null
      NotificationCompat.ProgressStyle.Point(boundary.end)
        .setColor(next.color ?: boundary.color)
    }
    if (points.isNotEmpty()) style.setProgressPoints(points)

    if (content.progress != null) {
      style.setProgress((content.progress.coerceIn(0.0, 1.0) * trackTotal).roundToInt())
    } else {
      style.setProgressIndeterminate(true)
    }

    // The three slots the track can carry: where it started, what is moving,
    // and where it ends. A Point holds only a position and a colour, so these
    // are the whole icon vocabulary Android gives a progress notification.
    trackIcon(content.startIcon)?.let(style::setProgressStartIcon)
    trackIcon(content.trackerIcon)?.let(style::setProgressTrackerIcon)
    trackIcon(content.endIcon)?.let(style::setProgressEndIcon)

    return style
  }

  private class Boundary(
    val segment: NotificationCompat.ProgressStyle.Segment,
    /** Cumulative offset of this segment's far edge. */
    val end: Int,
    val color: Int,
  )

  /**
   * Stage weights are relative, so they are scaled to fill the track and the
   * rounding error is absorbed by the last segment. Otherwise three equal
   * stages of 333 leave a one-unit gap the system draws as a sliver.
   */
  private fun segments(stages: List<Stage>, accent: Int): List<Boundary> {
    if (stages.isEmpty()) {
      // No stages means a plain bar: one segment spanning the whole track.
      return listOf(
        Boundary(
          NotificationCompat.ProgressStyle.Segment(trackTotal).setColor(accent),
          trackTotal,
          accent,
        ),
      )
    }

    val totalWeight = stages.sumOf { it.weight }
    var consumed = 0
    return stages.mapIndexed { index, stage ->
      val length =
        if (index == stages.lastIndex) trackTotal - consumed
        else (stage.weight / totalWeight * trackTotal).roundToInt().coerceAtLeast(1)
      consumed += length
      val color = stage.color ?: accent
      Boundary(
        NotificationCompat.ProgressStyle.Segment(length).setColor(color),
        consumed,
        color,
      )
    }
  }

  /**
   * The status-bar glyph. Falls back to the app icon, which is not ideal — a
   * full-colour launcher icon is drawn as a silhouette — but it is always
   * present, and a notification with no small icon does not post at all.
   */
  private fun smallIcon(content: LiveUpdateContent): Int =
    content.icon?.let { drawableRes(it) } ?: context.applicationInfo.icon

  /**
   * An icon for the track, from either a compiled resource or a file.
   *
   * Two sources because two situations. A drawable name covers the icons an
   * app knows at build time and is much the better option: a vector drawable
   * is rendered at the exact density SystemUI asks for, and costs nothing to
   * pass. A path covers the ones it does not — a courier's photo, a partner
   * brand fetched at runtime — which cannot be a compiled resource by
   * definition.
   */
  private fun trackIcon(value: String?): IconCompat? {
    if (value.isNullOrBlank()) return null

    val path = value.removePrefix("file://")
    if (path.startsWith("/")) return bitmapIcon(path)

    return drawableRes(value)?.let { IconCompat.createWithResource(context, it) }
      ?: run {
        // Silent absence is the worst outcome here: the track simply renders
        // without the icon and there is nothing anywhere to say why.
        Log.w(TAG, "icon \"$value\" is not a drawable in ${context.packageName}")
        null
      }
  }

  /**
   * Decode an image file, downscaled on the way in.
   *
   * The subsampling is not an optimisation. A notification crosses a Binder
   * transaction with a hard size limit around 1MB, and a full-resolution photo
   * blows through it — the failure is a TransactionTooLargeException that
   * takes down the posting process, not a notification that renders badly. The
   * track draws these at roughly 24dp, so anything past [MAX_ICON_PX] is
   * detail nobody can see being paid for in crash risk.
   */
  private fun bitmapIcon(path: String): IconCompat? {
    val file = File(path)
    if (!file.exists()) {
      Log.w(TAG, "icon file does not exist: $path")
      return null
    }
    return try {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(path, bounds)

      var sample = 1
      while (
        bounds.outWidth / sample > MAX_ICON_PX || bounds.outHeight / sample > MAX_ICON_PX
      ) {
        sample *= 2
      }

      val bitmap: Bitmap? =
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
      bitmap?.let(IconCompat::createWithBitmap)
        ?: run {
          Log.w(TAG, "icon file is not a decodable image: $path")
          null
        }
    } catch (e: Exception) {
      Log.w(TAG, "could not read icon $path", e)
      null
    }
  }

  /**
   * Resolve a drawable by name, because a name is all JS can send.
   *
   * `getIdentifier` is discouraged for good reasons — it is a runtime string
   * lookup that resource shrinking cannot see — but there is no alternative
   * when the identifier crosses a language boundary as text. Apps that shrink
   * resources should keep these named in `keep.xml`.
   */
  private fun drawableRes(name: String): Int? {
    val resources = context.resources
    val packageName = context.packageName
    @Suppress("DiscouragedApi")
    val id = resources.getIdentifier(name, "drawable", packageName)
      .takeIf { it != 0 }
      ?: resources.getIdentifier(name, "mipmap", packageName).takeIf { it != 0 }
    return id
  }

  private companion object {
    const val TAG = "LiveUpdate"
    /** Used when the caller sends no colour. */
    val DEFAULT_ACCENT = Color.parseColor("#C95942")
    /** The track draws icons at roughly 24dp; this is generous for any density. */
    const val MAX_ICON_PX = 192
    /** Android shows three actions and silently drops any beyond that. */
    const val MAX_ACTIONS = 3
    const val ACTION_CODE_STRIDE = 8
  }
}
