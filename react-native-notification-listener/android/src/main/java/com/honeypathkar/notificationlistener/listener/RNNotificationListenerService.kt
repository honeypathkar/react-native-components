package com.honeypathkar.notificationlistener.listener

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.honeypathkar.notificationlistener.detect.PaymentDeduper
import com.honeypathkar.notificationlistener.filter.DynamicFilterPipeline
import com.honeypathkar.notificationlistener.parser.PaymentParser
import com.honeypathkar.notificationlistener.storage.PaymentStore

class RNNotificationListenerService : NotificationListenerService() {

    companion object {
        const val TAG = "RNNotificationListener"
        var instance: RNNotificationListenerService? = null
            private set

        val filterPipeline = DynamicFilterPipeline()
        var eventEmitter: ((String, Map<String, Any?>) -> Unit)? = null
    }

    private lateinit var deduper: PaymentDeduper
    private lateinit var store: PaymentStore

    override fun onCreate() {
        super.onCreate()
        instance = this
        deduper = PaymentDeduper(this)
        store = PaymentStore(this)
        Log.d(TAG, "NotificationListenerService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val payload = NotificationPayload.from(sbn)

        // Run through dynamic filters
        if (!filterPipeline.shouldAccept(payload)) {
            return
        }

        // Deduplication check
        if (deduper.isDuplicateNotification(payload)) {
            return
        }

        // Emit raw notification payload event to JS
        val notificationMap = mapOf(
            "key" to payload.key,
            "packageName" to payload.packageName,
            "postedAt" to payload.postedAt,
            "title" to payload.title,
            "text" to payload.text,
            "bigText" to payload.bigText,
            "combinedText" to payload.combinedText
        )
        eventEmitter?.invoke("onNotificationReceived", notificationMap)

        // Parse for financial payment notifications if present
        val parsedPayment = PaymentParser.parse(payload)
        if (parsedPayment != null && parsedPayment.isCredit) {
            if (!deduper.isDuplicatePayment(parsedPayment)) {
                store.insertPayment(parsedPayment)

                val paymentMap = mapOf(
                    "amountPaise" to parsedPayment.amountPaise,
                    "isCredit" to parsedPayment.isCredit,
                    "payerName" to parsedPayment.payerName,
                    "upiRef" to parsedPayment.upiRef,
                    "sourcePackage" to parsedPayment.sourcePackage,
                    "postedAt" to parsedPayment.postedAt,
                    "rawText" to parsedPayment.rawText
                )
                eventEmitter?.invoke("onPaymentDetected", paymentMap)
            }
        }
    }
}
