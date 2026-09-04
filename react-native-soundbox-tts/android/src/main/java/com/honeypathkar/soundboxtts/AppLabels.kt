package com.honeypathkar.soundboxtts

import android.content.Context
import android.content.pm.PackageManager
import java.util.concurrent.ConcurrentHashMap

object AppLabels {
    private val cache = ConcurrentHashMap<String, String>()

    private val KNOWN_APPS = mapOf(
        "com.google.android.apps.nbu.paisa.user" to "Google Pay",
        "com.google.android.apps.nfc.plugin.card.bpay" to "Google Pay",
        "com.phonepe.app" to "PhonePe",
        "net.one97.paytm" to "Paytm",
        "in.org.npci.upiapp" to "BHIM",
        "org.npci.upiapp" to "BHIM",
        "in.amazon.mShop.android.shopping" to "Amazon Pay",
        "com.dreamplug.androidapp" to "CRED",
        "com.whatsapp" to "WhatsApp",
        "com.bharatpe.app" to "BharatPe",
        "com.samsung.android.spay" to "Samsung Pay",
        "com.truecaller" to "Truecaller",
        "com.airtel.payments.hub" to "Airtel",
        "com.freecharge.android" to "Freecharge",
        "com.mobikwik_new" to "MobiKwik",
        "com.naviapp" to "Navi",
        "money.super.payments" to "super.money",
        "com.nextbillion.groww" to "Groww",
        "com.moneyme.app" to "Slice",
        "money.jupiter" to "Jupiter",
        "com.epifi.fi" to "Fi Money",
        "com.jar.app" to "Jar",
        "com.fynace.app" to "Fynace",
        "com.csam.icici.bank.imobile" to "ICICI Bank",
        "com.msf.kbank.mobile" to "Kotak Bank",
        "com.axis.mobile" to "Axis Bank",
        "com.sbi.lotusintouch" to "SBI",
        "com.snapwork.hdfc" to "HDFC Bank",
    )

    fun resolveName(context: Context, rawNameOrPackage: String?): String? {
        if (rawNameOrPackage.isNullOrBlank()) return null

        // If it does not contain dot, it's already a clean app name (e.g. "Google Pay")
        if (!rawNameOrPackage.contains(".")) {
            return rawNameOrPackage
        }

        cache[rawNameOrPackage]?.let { return it }

        val resolved = KNOWN_APPS[rawNameOrPackage]
            ?: try {
                val pm = context.packageManager
                val info = pm.getApplicationInfo(rawNameOrPackage, 0)
                pm.getApplicationLabel(info).toString().takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            } ?: cleanPackageName(rawNameOrPackage)

        if (resolved != null) {
            cache[rawNameOrPackage] = resolved
        }
        return resolved
    }

    private fun cleanPackageName(packageName: String): String {
        val parts = packageName.split(".")
        return parts.lastOrNull()?.replaceFirstChar { it.uppercase() } ?: packageName
    }
}
