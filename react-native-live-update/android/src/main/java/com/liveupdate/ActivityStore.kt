package com.liveupdate

import android.content.Context
import android.util.Log
import com.liveupdate.models.LiveUpdateContent
import org.json.JSONObject

/**
 * What we know about the live updates this app has posted, kept across process
 * death.
 *
 * The in-memory map this replaces looked fine and failed in the field. Android
 * kills a backgrounded app freely while its notification stays on screen; when
 * a push wakes it back up and JS calls `update()`, an in-memory map is empty,
 * so the update is rejected as unknown and the user watches a delivery sit at
 * 40% until it is cancelled. The notification outlives the process, so its
 * bookkeeping has to as well.
 *
 * Deliberately small: an id, a name, a notification id, and the last content —
 * enough to rebuild the exact notification. Nothing here is a cache of app
 * data, and none of it should be sensitive: it is plain-text SharedPreferences
 * inside the app sandbox, and it is also on screen already.
 */
internal class ActivityStore(context: Context) {

  private val prefs =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  data class Entry(
    val id: String,
    val name: String,
    /** The Android notification id — the handle for notify() and cancel(). */
    val notificationId: Int,
    val content: LiveUpdateContent,
    /** Re-post this if the user swipes it away. */
    val persistent: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
  )

  /**
   * Read-modify-write over a single blob, so every mutation has to be
   * serialised against every other. `commit()` rather than `apply()` for the
   * same reason the store exists at all: `apply()` writes on a background
   * thread, and a process killed in between loses the entry for a
   * notification that is already on screen.
   */
  @Synchronized
  fun put(entry: Entry) {
    val all = readAll()
    all.put(
      entry.id,
      JSONObject().apply {
        put("name", entry.name)
        put("notificationId", entry.notificationId)
        put("content", entry.content.toJson())
        put("persistent", entry.persistent)
        put("createdAt", entry.createdAt)
        put("updatedAt", entry.updatedAt)
      },
    )
    prefs.edit().putString(KEY_ACTIVITIES, all.toString()).commit()
  }

  @Synchronized
  fun get(id: String): Entry? {
    val json = readAll().optJSONObject(id) ?: return null
    return parse(id, json)
  }

  @Synchronized
  fun all(): List<Entry> {
    val all = readAll()
    return all.keys().asSequence().mapNotNull { id ->
      all.optJSONObject(id)?.let { parse(id, it) }
    }.toList()
  }

  @Synchronized
  fun remove(id: String): Entry? {
    val all = readAll()
    val entry = all.optJSONObject(id)?.let { parse(id, it) } ?: return null
    all.remove(id)
    prefs.edit().putString(KEY_ACTIVITIES, all.toString()).commit()
    return entry
  }

  @Synchronized
  fun clear() {
    prefs.edit().remove(KEY_ACTIVITIES).commit()
  }

  /**
   * A notification id that is not in use by any live update we are tracking.
   *
   * Counts up and wraps inside a band well clear of zero, so it cannot collide
   * with a host app that posts its own notifications from a low id — the ids
   * share one namespace per app, and colliding means silently replacing
   * somebody else's notification.
   */
  @Synchronized
  fun nextNotificationId(): Int {
    val next = prefs.getInt(KEY_NEXT_ID, ID_BASE)
    val wrapped = if (next >= ID_BASE + ID_RANGE) ID_BASE else next
    prefs.edit().putInt(KEY_NEXT_ID, wrapped + 1).commit()
    return wrapped
  }

  private fun readAll(): JSONObject =
    try {
      JSONObject(prefs.getString(KEY_ACTIVITIES, "{}") ?: "{}")
    } catch (e: Exception) {
      // Corrupt blob — a partial write, or a downgrade to an older schema.
      // Starting empty loses bookkeeping for anything on screen, which
      // endAll() can still clear; throwing here would break every call.
      Log.w(TAG, "activity store unreadable, starting empty", e)
      JSONObject()
    }

  private fun parse(id: String, json: JSONObject): Entry? =
    try {
      Entry(
        id = id,
        name = json.optString("name"),
        notificationId = json.getInt("notificationId"),
        content = LiveUpdateContent.fromJson(json.getJSONObject("content")),
        persistent = json.optBoolean("persistent", false),
        createdAt = json.optLong("createdAt"),
        updatedAt = json.optLong("updatedAt"),
      )
    } catch (e: Exception) {
      Log.w(TAG, "dropping unreadable activity $id", e)
      null
    }

  private companion object {
    const val TAG = "LiveUpdate"
    const val PREFS = "com.liveupdate.activities"
    const val KEY_ACTIVITIES = "activities"
    const val KEY_NEXT_ID = "nextNotificationId"
    const val ID_BASE = 8000
    const val ID_RANGE = 1000
  }
}
