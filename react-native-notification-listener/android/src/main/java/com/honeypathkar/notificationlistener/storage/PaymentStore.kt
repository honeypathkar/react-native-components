package com.honeypathkar.notificationlistener.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.honeypathkar.notificationlistener.parser.PaymentParser

/**
 * SQLite Store of Record for offline notification persistence.
 */
class PaymentStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "rn_notification_payments.db"
        private const val DB_VERSION = 1
        private const val TABLE_PAYMENTS = "payments"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PAYMENTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                amount_paise INTEGER NOT NULL,
                payer_name TEXT,
                upi_ref TEXT,
                source_package TEXT NOT NULL,
                posted_at INTEGER NOT NULL,
                raw_text TEXT NOT NULL,
                dedup_key TEXT UNIQUE NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_posted_at ON $TABLE_PAYMENTS(posted_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PAYMENTS")
        onCreate(db)
    }

    fun insertPayment(payment: PaymentParser.ParsedPayment): Long {
        val db = writableDatabase
        val dedupKey = payment.upiRef?.let { "utr_$it" }
            ?: "fuzzy_${payment.amountPaise}_${PaymentParser.normalizePayer(payment.payerName)}_${payment.postedAt / 1000}"

        val values = ContentValues().apply {
            put("amount_paise", payment.amountPaise)
            put("payer_name", payment.payerName)
            put("upi_ref", payment.upiRef)
            put("source_package", payment.sourcePackage)
            put("posted_at", payment.postedAt)
            put("raw_text", payment.rawText)
            put("dedup_key", dedupKey)
        }
        return db.insertWithOnConflict(TABLE_PAYMENTS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun getPayments(limit: Int = 40, offset: Int = 0): List<Map<String, Any?>> {
        val list = mutableListOf<Map<String, Any?>>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_PAYMENTS,
            null,
            null,
            null,
            null,
            null,
            "posted_at DESC",
            "$offset, $limit"
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                val item = mapOf(
                    "id" to c.getLong(c.getColumnIndexOrThrow("id")),
                    "amountPaise" to c.getLong(c.getColumnIndexOrThrow("amount_paise")),
                    "payerName" to c.getString(c.getColumnIndexOrThrow("payer_name")),
                    "upiRef" to c.getString(c.getColumnIndexOrThrow("upi_ref")),
                    "sourcePackage" to c.getString(c.getColumnIndexOrThrow("source_package")),
                    "postedAt" to c.getLong(c.getColumnIndexOrThrow("posted_at")),
                    "rawText" to c.getString(c.getColumnIndexOrThrow("raw_text"))
                )
                list.add(item)
            }
        }
        return list
    }
}
