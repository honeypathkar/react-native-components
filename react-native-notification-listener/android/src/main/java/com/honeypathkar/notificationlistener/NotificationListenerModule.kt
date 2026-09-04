package com.honeypathkar.notificationlistener

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.honeypathkar.notificationlistener.listener.RNNotificationListenerService
import com.honeypathkar.notificationlistener.storage.PaymentStore

class NotificationListenerModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val paymentStore = PaymentStore(reactContext)

    init {
        RNNotificationListenerService.eventEmitter = { eventName, params ->
            val WritableMap = Arguments.makeNativeMap(params)
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(eventName, WritableMap)
        }
    }

    override fun getName(): String = "RNNotificationListener"

    @ReactMethod
    fun isPermissionGranted(promise: Promise) {
        val packageName = reactContext.packageName
        val flat = Settings.Secure.getString(reactContext.contentResolver, "enabled_notification_listeners")
        val isGranted = flat != null && flat.contains(packageName)
        promise.resolve(isGranted)
    }

    @ReactMethod
    fun openPermissionSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        reactContext.startActivity(intent)
    }

    @ReactMethod
    fun configureFilters(config: ReadableMap) {
        if (config.hasKey("packageAllowlist")) {
            val array = config.getArray("packageAllowlist")
            val set = mutableSetOf<String>()
            if (array != null) {
                for (i in 0 until array.size()) {
                    array.getString(i)?.let { set.add(it) }
                }
            }
            RNNotificationListenerService.filterPipeline.packageAllowlist = set
        }
        if (config.hasKey("ignoreSummaries")) {
            RNNotificationListenerService.filterPipeline.ignoreSummaries = config.getBoolean("ignoreSummaries")
        }
        if (config.hasKey("ignoreChatMessages")) {
            RNNotificationListenerService.filterPipeline.ignoreChatMessages = config.getBoolean("ignoreChatMessages")
        }
        if (config.hasKey("ignoreOngoing")) {
            RNNotificationListenerService.filterPipeline.ignoreOngoing = config.getBoolean("ignoreOngoing")
        }
    }

    @ReactMethod
    fun getPayments(limit: Int, offset: Int, promise: Promise) {
        try {
            val list = paymentStore.getPayments(limit, offset)
            val writableArray = Arguments.createArray()
            for (item in list) {
                writableArray.pushMap(Arguments.makeNativeMap(item))
            }
            promise.resolve(writableArray)
        } catch (e: Exception) {
            promise.reject("DB_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun addListener(eventName: String) {}

    @ReactMethod
    fun removeListeners(count: Int) {}
}
