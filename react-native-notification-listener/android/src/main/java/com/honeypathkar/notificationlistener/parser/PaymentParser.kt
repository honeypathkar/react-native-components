package com.honeypathkar.notificationlistener.parser

import com.honeypathkar.notificationlistener.listener.NotificationPayload

/**
 * Utility for parsing payment notifications (amounts in paise, UPI ref / UTR, payer name, credit vs debit).
 */
object PaymentParser {

    private val AMOUNT = Regex(
        """(?:₹|\bRs\.?|\bINR)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE,
    )

    private val UPI_REF = Regex("""\b(\d{12})\b""")

    private val DEBIT_MARKERS = listOf(
        "debited", "you paid", "you've paid", "you have paid", "paid to", "sent to",
        "payment to", "requesting", "has requested", "requested money", "is requesting",
        "reminder", "will be debited", "autopay", "mandate", "e-mandate",
        "low balance", "insufficient", "failed", "declined", "unsuccessful",
        "cancelled", "canceled", "reversed", "refund", "cashback", "spent",
        "due", "overdue", "bill", "recharge",
    )

    private val CREDIT_MARKERS = listOf(
        "received", "credited", "paid you", "has paid", "sent you", "has sent", "sent",
        "transferred", "has transferred", "money received", "payment received", "you got",
        "received from", "deposited", "paid",
    )

    private val STRICT_PAYMENT_OPENING = Regex(
        """^\s*(?:payment\s+received|received\s+payment|payment\s+of)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val PAYER_PATTERNS = listOf(
        Regex("""(?:from|by)\s+([A-Z][A-Za-z.\s]{1,30}?)(?=\s*(?:[|.,(]|$|\bon\b|\bvia\b|\bto\b|\bhas\b|\bfor\b))"""),
        Regex("""(?:^|[|•\n])\s*([A-Z][A-Za-z.\s]{1,30}?)\s+(?:has\s+sent|sent\s+you|has\s+paid|paid\s+you|has\s+transferred|transferred)"""),
        Regex("""(?:^|[|•\n])\s*([A-Z][A-Za-z.\s]{1,30}?)\s+(?:paid|sent)\s+(?:₹|Rs|INR|\b[0-9])""", RegexOption.IGNORE_CASE),
    )

    data class ParsedPayment(
        val amountPaise: Long,
        val isCredit: Boolean,
        val payerName: String?,
        val upiRef: String?,
        val sourcePackage: String,
        val postedAt: Long,
        val rawText: String,
    )

    fun parse(payload: NotificationPayload): ParsedPayment? =
        parse(payload.combinedText, payload.packageName, payload.postedAt)

    fun isExplicitPaymentMessage(bodyText: String?): Boolean {
        val body = bodyText?.trim().orEmpty()
        if (body.isEmpty() || body.contains("?")) return false
        return STRICT_PAYMENT_OPENING.containsMatchIn(body)
    }

    fun parse(text: String, sourcePackage: String, postedAt: Long): ParsedPayment? {
        if (text.isBlank()) return null
        val haystack = text.lowercase()

        val amountPaise = extractAmountPaise(text) ?: return null
        val isDebit = DEBIT_MARKERS.any { haystack.contains(it) }
        val isCredit = !isDebit && CREDIT_MARKERS.any { haystack.contains(it) }

        return ParsedPayment(
            amountPaise = amountPaise,
            isCredit = isCredit,
            payerName = extractPayer(text),
            upiRef = UPI_REF.find(text)?.groupValues?.get(1),
            sourcePackage = sourcePackage,
            postedAt = postedAt,
            rawText = text,
        )
    }

    fun extractAmountPaise(text: String): Long? {
        val raw = AMOUNT.find(text)?.groupValues?.get(1)?.replace(",", "") ?: return null
        val parts = raw.split(".")
        val rupees = parts[0].toLongOrNull() ?: return null
        val paise = when {
            parts.size < 2 -> 0L
            parts[1].length == 1 -> parts[1].toLongOrNull()?.times(10) ?: 0L
            else -> parts[1].take(2).toLongOrNull() ?: 0L
        }
        val total = rupees * 100 + paise
        return if (total > 0) total else null
    }

    private fun extractPayer(text: String): String? {
        for (pattern in PAYER_PATTERNS) {
            val match = pattern.find(text)?.groupValues?.getOrNull(1)?.trim()
            if (!match.isNullOrBlank() && match.length in 2..30) return match
        }
        return null
    }

    fun normalizePayer(name: String?): String =
        name?.lowercase()?.replace(Regex("[^a-z0-9]"), "") ?: ""
}
