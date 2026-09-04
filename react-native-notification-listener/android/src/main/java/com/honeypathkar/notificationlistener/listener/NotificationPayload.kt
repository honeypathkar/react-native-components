package com.honeypathkar.notificationlistener.listener

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification

/**
 * Flattened view of a posted Android notification.
 * Captures all 7 text slots separately and combined.
 */
data class NotificationPayload(
    val key: String,
    val packageName: String,
    val postedAt: Long,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val summaryText: String?,
    val infoText: String?,
    val textLines: List<String>,
    val isGroupSummary: Boolean,
    val isOngoing: Boolean,
    val isChatMessage: Boolean,
) {
    val combinedText: String by lazy {
        buildList {
            add(title)
            add(text)
            add(bigText)
            add(subText)
            add(summaryText)
            add(infoText)
            addAll(textLines)
        }.filterNot { it.isNullOrBlank() }.joinToString(" | ")
    }

    val contentHash: Int by lazy { combinedText.hashCode() }

    companion object {
        private fun Bundle.string(key: String): String? =
            getCharSequence(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        fun from(sbn: StatusBarNotification): NotificationPayload {
            val extras = sbn.notification?.extras ?: Bundle()
            val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                ?: emptyList()

            val flags = sbn.notification?.flags ?: 0

            val template = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
            val isChat = template.contains("MessagingStyle") ||
                extras.containsKey(Notification.EXTRA_MESSAGES) ||
                extras.containsKey(Notification.EXTRA_CONVERSATION_TITLE) ||
                extras.containsKey("android.messagingUser")

            return NotificationPayload(
                key = sbn.key,
                packageName = sbn.packageName,
                postedAt = sbn.postTime,
                title = extras.string(Notification.EXTRA_TITLE)
                    ?: extras.string(Notification.EXTRA_TITLE_BIG),
                text = extras.string(Notification.EXTRA_TEXT),
                bigText = extras.string(Notification.EXTRA_BIG_TEXT),
                subText = extras.string(Notification.EXTRA_SUB_TEXT),
                summaryText = extras.string(Notification.EXTRA_SUMMARY_TEXT),
                infoText = extras.string(Notification.EXTRA_INFO_TEXT),
                textLines = lines,
                isGroupSummary = (flags and Notification.FLAG_GROUP_SUMMARY) != 0,
                isOngoing = (flags and Notification.FLAG_ONGOING_EVENT) != 0,
                isChatMessage = isChat,
            )
        }
    }
}
