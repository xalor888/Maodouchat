package com.maodouchat.server.repository

import com.google.re2j.Pattern
import java.util.concurrent.atomic.AtomicLong

class ModerationRuleRepository {
    companion object {
        const val MAX_MATCHED_LENGTH = 280
        const val MAX_EVALUATE_CONTENT_LENGTH = 12_000
        const val REGEX_FLAGS = Pattern.CASE_INSENSITIVE or Pattern.MULTILINE
        const val MAX_WINDOW_MS = 30L * 24L * 60L * 60L * 1000L
        val REVIEW_ACTIONS = setOf("WARN_MOD", "AUTO_RATE_LIMIT", "AUTO_HOLD", "AUTO_DELETE")
        val ALLOWED_ACTIONS = setOf("WARN_MOD", "AUTO_HOLD", "AUTO_DELETE", "AUTO_RATE_LIMIT")
        val ALLOWED_SCOPES = setOf("ALL", "POST", "COMMENT")
        val ALLOWED_MATCH_TYPES = setOf("KEYWORD", "REGEX", "URL", "FREQUENCY")
        val ACTION_RANK = mapOf("WARN_MOD" to 1, "AUTO_RATE_LIMIT" to 2, "AUTO_HOLD" to 3, "AUTO_DELETE" to 4)
        const val CLEANUP_INTERVAL_MS = 24L * 60L * 60L * 1000L
        const val EVENT_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
        val lastCleanupAt = AtomicLong(0)

        fun compileModerationRegex(pattern: String): Pattern = Pattern.compile(pattern, REGEX_FLAGS)
    }
}
