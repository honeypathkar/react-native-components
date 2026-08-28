package com.predictiveback

import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.BackEventCompat
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule

class PredictiveBackModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            emit(EVENT_START, backEvent.toEventMap())
        }
        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            emit(EVENT_PROGRESS, backEvent.toEventMap())
        }
        override fun handleOnBackCancelled() {
            emit(EVENT_CANCEL, Arguments.createMap())
        }
        override fun handleOnBackPressed() {
            emit(EVENT_COMMIT, Arguments.createMap())
        }
    }

    private var isRegistered = false
    private var reactCallback: OnBackPressedCallback? = null
    private var reactCallbackHost: ComponentActivity? = null

    override fun getName(): String = NAME

    override fun getConstants(): MutableMap<String, Any> =
        hashMapOf(
            "progressAvailable" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        )

    @ReactMethod
    fun setMode(mode: String) {
        UiThreadUtil.runOnUiThread {
            val activity = reactApplicationContext.currentActivity as? ComponentActivity
            when (mode) {
                MODE_APP -> {
                    if (activity != null) {
                        attachTo(activity)
                        setReactCallbackEnabled(activity, true)
                    }
                    backCallback.isEnabled = true
                }
                MODE_SYSTEM -> {
                    backCallback.isEnabled = false
                    if (activity != null) setReactCallbackEnabled(activity, false)
                }
                MODE_DEFAULT -> {
                    backCallback.isEnabled = false
                    if (activity != null) setReactCallbackEnabled(activity, true)
                }
                else -> Log.w(NAME, "Unknown back mode: $mode")
            }
        }
    }

    @ReactMethod fun addListener(eventName: String) = Unit
    @ReactMethod fun removeListeners(count: Int) = Unit

    override fun invalidate() {
        UiThreadUtil.runOnUiThread {
            backCallback.isEnabled = false
            if (isRegistered) { backCallback.remove(); isRegistered = false }
            reactCallback?.isEnabled = true
            reactCallback = null
            reactCallbackHost = null
        }
        super.invalidate()
    }

    private fun attachTo(activity: ComponentActivity) {
        if (isRegistered) backCallback.remove()
        activity.onBackPressedDispatcher.addCallback(backCallback)
        isRegistered = true
    }

    private fun setReactCallbackEnabled(activity: ComponentActivity, enabled: Boolean) {
        findReactCallback(activity)?.isEnabled = enabled
    }

    private fun findReactCallback(activity: ComponentActivity): OnBackPressedCallback? {
        if (reactCallbackHost === activity) return reactCallback
        var cls: Class<*>? = activity.javaClass
        while (cls != null && cls != ComponentActivity::class.java) {
            for (field in cls.declaredFields) {
                if (OnBackPressedCallback::class.java.isAssignableFrom(field.type)) {
                    try {
                        field.isAccessible = true
                        val found = field.get(activity) as? OnBackPressedCallback
                        if (found != null) {
                            reactCallback = found; reactCallbackHost = activity
                            return found
                        }
                    } catch (e: Exception) {
                        Log.w(NAME, "Could not read React Native's back callback", e)
                        return null
                    }
                }
            }
            cls = cls.superclass
        }
        Log.w(NAME, "React Native's back callback not found; system back mode is a no-op")
        return null
    }

    private fun emit(event: String, payload: WritableMap) {
        val context = reactApplicationContext
        if (!context.hasActiveReactInstance()) return
        context.emitDeviceEvent(event, payload)
    }

    private fun BackEventCompat.toEventMap(): WritableMap =
        Arguments.createMap().apply {
            putDouble("progress", progress.toDouble())
            putInt("swipeEdge", swipeEdge)        // 0 = left, 1 = right
            putDouble("touchX", touchX.toDouble())
            putDouble("touchY", touchY.toDouble())
        }

    companion object {
        const val NAME = "PredictiveBackModule"
        private const val MODE_APP     = "app"
        private const val MODE_DEFAULT = "default"
        private const val MODE_SYSTEM  = "system"
        private const val EVENT_START    = "predictiveBackStart"
        private const val EVENT_PROGRESS = "predictiveBackProgress"
        private const val EVENT_CANCEL   = "predictiveBackCancel"
        private const val EVENT_COMMIT   = "predictiveBackCommit"
    }
}