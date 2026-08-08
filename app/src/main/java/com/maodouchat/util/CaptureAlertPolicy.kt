package com.maodouchat.util

/**
 * Peer-visible capture alerts for secret / disappearing chats.
 * Travel as normal E2EE TEXT so the server never sees plaintext.
 */
object CaptureAlertPolicy {
    const val PREFIX = "CAPTURE_ALERT:"

    fun format(localUserLabel: String, kind: String = "screenshot"): String {
        val who = localUserLabel.trim().ifBlank { "peer" }.take(32)
        val k = kind.trim().ifBlank { "screenshot" }.take(24)
        return "$PREFIX$k|$who took a $k (privacy alert)"
    }

    fun parse(content: String): Pair<String, String>? {
        if (!content.startsWith(PREFIX)) return null
        val body = content.removePrefix(PREFIX)
        val kind = body.substringBefore('|').ifBlank { "screenshot" }
        val rest = body.substringAfter('|', body)
        return kind to rest
    }

    fun isCaptureAlert(content: String): Boolean = content.startsWith(PREFIX)
}
