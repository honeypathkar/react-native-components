package com.honeypathkar.notificationlistener.filter

import com.honeypathkar.notificationlistener.listener.NotificationPayload

/**
 * Dynamic filtering rules for incoming notifications.
 */
class DynamicFilterPipeline {
    var packageAllowlist: Set<String> = emptySet()
    var ignoreSummaries: Boolean = true
    var ignoreChatMessages: Boolean = true
    var ignoreOngoing: Boolean = true
    var regexMatchRules: List<Regex> = emptyList()

    fun shouldAccept(payload: NotificationPayload): Boolean {
        // Filter 1: Allowlist check (if non-empty)
        if (packageAllowlist.isNotEmpty() && !packageAllowlist.contains(payload.packageName)) {
            return false
        }

        // Filter 2: Summary / ongoing check
        if (ignoreSummaries && payload.isGroupSummary) return false
        if (ignoreOngoing && payload.isOngoing) return false

        // Filter 3: Chat message check
        if (ignoreChatMessages && payload.isChatMessage) return false

        // Filter 4: Custom regex matching rules (if any configured)
        if (regexMatchRules.isNotEmpty()) {
            val text = payload.combinedText
            val matches = regexMatchRules.any { it.containsMatchIn(text) }
            if (!matches) return false
        }

        return true
    }
}
