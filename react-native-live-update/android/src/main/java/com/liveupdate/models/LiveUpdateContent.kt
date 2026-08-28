package com.liveupdate.models

import android.graphics.Color
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * One leg of the progress track.
 *
 * `weight` is relative, not absolute: the builder scales the whole set to fill
 * the track, so a caller can say "this leg is twice the other" without knowing
 * anything about `ProgressStyle`'s integer coordinate space.
 */
data class Stage(
  val id: String,
  val title: String,
  val weight: Double,
  /** Parsed ARGB, or null to use the activity's accent. */
  val color: Int?,
  /** Draw a marker where this stage begins. Off unless asked for. */
  val milestone: Boolean,
)

/** A button on the live update. */
data class Action(val id: String, val title: String, val deepLink: String?)

/**
 * Everything JS sends for one live update, parsed once at the bridge.
 *
 * A data class rather than the `ReadableMap` passed straight through: the map
 * is only valid for the duration of the bridge call, and this outlives it —
 * it is written to the store so an update after a process restart still has a
 * name and an icon to rebuild from.
 */
data class LiveUpdateContent(
  val title: String,
  val message: String?,
  val status: String?,
  /** 0..1, or null for an indeterminate track. */
  val progress: Double?,
  val stages: List<Stage>,
  /** Epoch milliseconds, or null for no countdown. */
  val endsAt: Long?,
  val icon: String?,
  val trackerIcon: String?,
  val startIcon: String?,
  val endIcon: String?,
  /** Parsed ARGB accent, or null for the package default. */
  val color: Int?,
  val deepLink: String?,
  val actions: List<Action>,
) {
  fun toJson(): JSONObject =
    JSONObject().apply {
      put("title", title)
      putOpt("message", message)
      putOpt("status", status)
      progress?.let { put("progress", it) }
      endsAt?.let { put("endsAt", it) }
      putOpt("icon", icon)
      putOpt("trackerIcon", trackerIcon)
      putOpt("startIcon", startIcon)
      putOpt("endIcon", endIcon)
      color?.let { put("color", it) }
      putOpt("deepLink", deepLink)
      if (actions.isNotEmpty()) {
        put(
          "actions",
          JSONArray().apply {
            actions.forEach { action ->
              put(
                JSONObject().apply {
                  put("id", action.id)
                  put("title", action.title)
                  action.deepLink?.let { put("deepLink", it) }
                },
              )
            }
          },
        )
      }
      if (stages.isNotEmpty()) {
        put(
          "stages",
          JSONArray().apply {
            stages.forEach { stage ->
              put(
                JSONObject().apply {
                  put("id", stage.id)
                  put("title", stage.title)
                  put("weight", stage.weight)
                  stage.color?.let { put("color", it) }
                  if (stage.milestone) put("milestone", true)
                },
              )
            }
          },
        )
      }
    }

  companion object {
    /** What a stage weighs when the caller does not say. */
    private const val DEFAULT_WEIGHT = 1.0

    fun from(map: ReadableMap): LiveUpdateContent =
      LiveUpdateContent(
        // JS validation guarantees a title; the fallback is for a native
        // caller (an FCM service) that skipped the JS layer entirely.
        title = map.string("title") ?: "",
        message = map.string("message"),
        status = map.string("status"),
        progress = map.double("progress"),
        stages = map.array("stages")?.let(::parseStages).orEmpty(),
        endsAt = map.double("endsAt")?.toLong(),
        icon = map.string("icon"),
        trackerIcon = map.string("trackerIcon"),
        startIcon = map.string("startIcon"),
        endIcon = map.string("endIcon"),
        color = parseColor(map.string("color")),
        deepLink = map.string("deepLink"),
        actions = map.array("actions")?.let(::parseActions).orEmpty(),
      )

    fun fromJson(json: JSONObject): LiveUpdateContent =
      LiveUpdateContent(
        title = json.optString("title"),
        message = json.stringOrNull("message"),
        status = json.stringOrNull("status"),
        progress = if (json.has("progress")) json.getDouble("progress") else null,
        stages = json.optJSONArray("stages")?.let { array ->
          (0 until array.length()).map { i ->
            val stage = array.getJSONObject(i)
            Stage(
              id = stage.optString("id"),
              title = stage.optString("title"),
              weight = stage.optDouble("weight", DEFAULT_WEIGHT),
              color = if (stage.has("color")) stage.getInt("color") else null,
              milestone = stage.optBoolean("milestone", false),
            )
          }
        }.orEmpty(),
        endsAt = if (json.has("endsAt")) json.getLong("endsAt") else null,
        icon = json.stringOrNull("icon"),
        trackerIcon = json.stringOrNull("trackerIcon"),
        startIcon = json.stringOrNull("startIcon"),
        endIcon = json.stringOrNull("endIcon"),
        color = if (json.has("color")) json.getInt("color") else null,
        deepLink = json.stringOrNull("deepLink"),
        actions = json.optJSONArray("actions")?.let { array ->
          (0 until array.length()).map { i ->
            val action = array.getJSONObject(i)
            Action(
              id = action.optString("id"),
              title = action.optString("title"),
              deepLink = action.stringOrNull("deepLink"),
            )
          }
        }.orEmpty(),
      )

    private fun parseActions(array: ReadableArray): List<Action> =
      (0 until array.size()).mapNotNull { i ->
        val action = array.getMap(i) ?: return@mapNotNull null
        Action(
          id = action.string("id") ?: return@mapNotNull null,
          title = action.string("title") ?: return@mapNotNull null,
          deepLink = action.string("deepLink"),
        )
      }

    private fun parseStages(array: ReadableArray): List<Stage> =
      (0 until array.size()).mapNotNull { i ->
        val stage = array.getMap(i) ?: return@mapNotNull null
        Stage(
          id = stage.string("id") ?: return@mapNotNull null,
          title = stage.string("title").orEmpty(),
          weight = stage.double("weight")?.takeIf { it > 0 } ?: DEFAULT_WEIGHT,
          color = parseColor(stage.string("color")),
          milestone = stage.boolean("milestone") ?: false,
        )
      }

    /**
     * Colours arrive validated from JS, but a native caller can send anything
     * and `Color.parseColor` throws on a bad string. A wrong colour is not
     * worth failing a delivery notification over.
     */
    private fun parseColor(value: String?): Int? =
      value?.let {
        try {
          Color.parseColor(it)
        } catch (_: IllegalArgumentException) {
          null
        }
      }
  }
}

// ─── ReadableMap without the ceremony ────────────────────────────────────────
// Every getter on ReadableMap throws if the key is absent AND returns a
// non-null type that can still be JS null, so each read needs the same two
// guards. These put them in one place.

private fun ReadableMap.has(key: String) = hasKey(key) && !isNull(key)

private fun ReadableMap.string(key: String): String? =
  if (has(key)) getString(key) else null

private fun ReadableMap.double(key: String): Double? =
  if (has(key)) getDouble(key) else null

private fun ReadableMap.array(key: String): ReadableArray? =
  if (has(key)) getArray(key) else null

private fun ReadableMap.boolean(key: String): Boolean? =
  if (has(key)) getBoolean(key) else null

private fun JSONObject.stringOrNull(key: String): String? =
  if (has(key) && !isNull(key)) getString(key) else null
