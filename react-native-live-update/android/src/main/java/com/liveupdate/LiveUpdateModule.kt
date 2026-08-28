package com.liveupdate

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.Arguments
import com.liveupdate.models.LiveUpdateContent

/**
 * The bridge, and nothing else.
 *
 * Marshals arguments, calls [LiveUpdateManager], turns exceptions into the
 * error codes JS branches on. All of the behaviour lives in the manager so
 * that a native caller with no bridge — the `FirebaseMessagingService` that
 * receives a delivery update while the app is backgrounded — can drive the
 * same code.
 *
 * Written against the legacy bridge macros deliberately: they work unchanged
 * under both the old architecture and the New Architecture's interop layer,
 * which is what lets one build of this package support a wide range of React
 * Native versions.
 */
class LiveUpdateModule(reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {

  private val manager = LiveUpdateManager(reactContext)

  override fun getName() = NAME

  @ReactMethod
  fun isSupported(promise: Promise) = guard(promise) { manager.support() }

  @ReactMethod
  fun getCapabilities(promise: Promise) = guard(promise) { manager.capabilities() }

  @ReactMethod
  fun configureNotifications(config: ReadableMap, promise: Promise) =
    guard(promise) { manager.configureChannel(config) }

  @ReactMethod
  fun start(
    id: String,
    name: String,
    content: ReadableMap,
    options: ReadableMap,
    promise: Promise,
  ) =
    guard(promise) {
      manager.start(
        id,
        name,
        LiveUpdateContent.from(content),
        persistent = options.hasKey("persistent") && options.getBoolean("persistent"),
      )
      // Shaped like the iOS handle, minus pushToken: Android has no
      // per-activity push channel to hand back a token for.
      Arguments.createMap().apply { putString("id", id) }
    }

  @ReactMethod
  fun update(id: String, content: ReadableMap, promise: Promise) =
    guard(promise) { manager.update(id, LiveUpdateContent.from(content)) }

  @ReactMethod
  fun end(id: String, dismissAfterMs: Double, promise: Promise) =
    guard(promise) { manager.end(id, dismissAfterMs.toLong()) }

  @ReactMethod
  fun getRunning(promise: Promise) =
    guard(promise) {
      Arguments.createArray().apply { manager.running().forEach(::pushString) }
    }

  @ReactMethod
  fun endAll(promise: Promise) = guard(promise) { manager.endAll() }

  /**
   * Resolve with whatever the block returned, or reject with a code.
   *
   * A bare `Exception` becomes a generic code rather than being swallowed or
   * re-thrown across the bridge, where it would surface in JS as an unhandled
   * native crash with no stack worth reading.
   */
  private inline fun guard(promise: Promise, block: () -> Any?) {
    try {
      when (val result = block()) {
        is Unit -> promise.resolve(null)
        else -> promise.resolve(result)
      }
    } catch (e: LiveUpdateManager.LiveUpdateException) {
      promise.reject(e.code, e.message, e)
    } catch (e: Exception) {
      promise.reject("START_FAILED", e.message ?: e.toString(), e)
    }
  }

  companion object {
    const val NAME = "LiveUpdate"
  }
}
