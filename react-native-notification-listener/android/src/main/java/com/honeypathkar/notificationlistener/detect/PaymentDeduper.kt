package com.honeypathkar.notificationlistener.detect

import android.content.Context
import com.honeypathkar.notificationlistener.listener.NotificationPayload
import com.honeypathkar.notificationlistener.parser.PaymentParser

/**
 * 3-layer deduplication engine:
 * 1. Notification key + hash (in-memory, 200 items)
 * 2. Transaction identity (UTR / UPI ref, persisted)
 * 3. Fuzzy time window (amount + payer, 90 seconds, persisted)
 */
class PaymentDeduper(context: Context) {
    private val prefs = context.getSharedPreferences("rn_notification_dedup", Context.MODE_PRIVATE)

    private val seenKeys = object : LinkedHashMap<String, Long>(200, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > 200
        }
    }

    @Synchronized
    fun isDuplicateNotification(payload: NotificationPayload): Boolean {
        val compositeKey = "${payload.key}:${payload.contentHash}"
        if (seenKeys.containsKey(compositeKey)) {
            return true
        }
        seenKeys[compositeKey] = payload.postedAt
        return false
    }

    @Synchronized
    fun isDuplicatePayment(parsed: PaymentParser.ParsedPayment): Boolean {
        cleanExpiredEntries()

        // Layer 2: UTR ref check
        if (!parsed.upiRef.isNullOrBlank()) {
            val key = "utr_${parsed.upiRef}"
            if (prefs.contains(key)) return true
            prefs.edit().putLong(key, parsed.postedAt).apply()
            return false
        }

        // Layer 3: Fuzzy window check (amount + normalized payer)
        val payerNorm = PaymentParser.normalizePayer(parsed.payerName)
        val fuzzyKey = "fuzzy_${parsed.amountPaise}_$payerNorm"
        val lastSeen = prefs.getLong(fuzzyKey, 0L)
        val now = System.currentTimeMillis()

        if (lastSeen > 0 && (now - lastSeen) < 90_000L) {
            return true
        }

        prefs.edit().putLong(fuzzyKey, now).apply()
        return false
    }

    private fun cleanExpiredEntries() {
        val now = System.currentTimeMillis()
        val editor = prefs.edit()
        var changed = false

        for ((key, value) in prefs.all) {
            val timestamp = (value as? Long) ?: continue
            if (key.startsWith("fuzzy_") && (now - timestamp) > 90_000L) {
                editor.remove(key)
                changed = true
            } else if (key.startsWith("utr_") && (now - timestamp) > 86_400_000L * 7) {
                editor.remove(key)
                changed = true
            }
        }
        if (changed) editor.apply()
    }
}
