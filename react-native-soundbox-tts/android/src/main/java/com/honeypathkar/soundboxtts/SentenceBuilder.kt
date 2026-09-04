package com.honeypathkar.soundboxtts

import android.content.Context

object SentenceBuilder {

    private data class LanguageStrings(
        val amount: Int,
        val amountWithPaise: Int,
        val from: Int,
        val on: Int,
        val suffix: Int,
        val ready: Int,
    )

    private fun stringsFor(language: String): LanguageStrings = when (language.lowercase()) {
        "en" -> LanguageStrings(
            R.string.tts_amount_en, R.string.tts_amount_paise_en,
            R.string.tts_from_en, R.string.tts_on_en, 0, R.string.tts_ready_en,
        )
        "hi" -> LanguageStrings(
            R.string.tts_amount_hi, R.string.tts_amount_paise_hi,
            R.string.tts_from_hi, R.string.tts_on_hi,
            R.string.tts_suffix_hi, R.string.tts_ready_hi,
        )
        "mr" -> LanguageStrings(
            R.string.tts_amount_mr, R.string.tts_amount_paise_mr,
            R.string.tts_from_mr, R.string.tts_on_mr,
            R.string.tts_suffix_mr, R.string.tts_ready_mr,
        )
        "bn" -> LanguageStrings(
            R.string.tts_amount_bn, R.string.tts_amount_paise_bn,
            R.string.tts_from_bn, R.string.tts_on_bn,
            R.string.tts_suffix_bn, R.string.tts_ready_bn,
        )
        "gu" -> LanguageStrings(
            R.string.tts_amount_gu, R.string.tts_amount_paise_gu,
            R.string.tts_from_gu, R.string.tts_on_gu,
            R.string.tts_suffix_gu, R.string.tts_ready_gu,
        )
        "ta" -> LanguageStrings(
            R.string.tts_amount_ta, R.string.tts_amount_paise_ta,
            R.string.tts_from_ta, R.string.tts_on_ta,
            R.string.tts_suffix_ta, R.string.tts_ready_ta,
        )
        "te" -> LanguageStrings(
            R.string.tts_amount_te, R.string.tts_amount_paise_te,
            R.string.tts_from_te, R.string.tts_on_te,
            R.string.tts_suffix_te, R.string.tts_ready_te,
        )
        "kn" -> LanguageStrings(
            R.string.tts_amount_kn, R.string.tts_amount_paise_kn,
            R.string.tts_from_kn, R.string.tts_on_kn,
            R.string.tts_suffix_kn, R.string.tts_ready_kn,
        )
        else -> LanguageStrings(
            R.string.tts_amount_en, R.string.tts_amount_paise_en,
            R.string.tts_from_en, R.string.tts_on_en, 0, R.string.tts_ready_en,
        )
    }

    fun buildPaymentSentence(
        context: Context,
        amountPaise: Long,
        payerName: String? = null,
        appName: String? = null,
        language: String = "hi"
    ): String {
        val strings = stringsFor(language)
        val rupees = amountPaise / 100
        val paise = (amountPaise % 100).toInt()

        val amount = if (paise == 0) {
            context.getString(strings.amount, rupees.toString())
        } else {
            context.getString(strings.amountWithPaise, rupees.toString(), paise.toString())
        }

        val payer = payerName?.takeIf { it.isNotBlank() }
        val app = AppLabels.resolveName(context, appName)

        return if (language.lowercase() == "en") {
            buildString {
                append(context.getString(R.string.tts_prefix_en))
                append(amount)
                payer?.let { append(context.getString(strings.from, it)) }
                app?.let { append(context.getString(strings.on, it)) }
            }
        } else {
            buildString {
                app?.let { append(context.getString(strings.on, it)) }
                payer?.let { append(context.getString(strings.from, it)) }
                append(amount)
                if (strings.suffix != 0) {
                    append(context.getString(strings.suffix))
                }
            }
        }
    }
}
