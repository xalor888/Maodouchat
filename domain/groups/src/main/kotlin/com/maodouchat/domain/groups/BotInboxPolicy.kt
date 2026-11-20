package com.maodouchat.domain.groups

import com.maodouchat.core.model.SlashCommand

/**
 * 群成员互发仍走 Sender Key。只有用户主动打出的 /命令、@bot、以及与 bot 的私聊明文，
 * 才另送 bot-inbox。密文一律不当命令。
 */
object BotInboxPolicy {
    private val SLASH = Regex("^/([a-z][a-z0-9_]{0,31})(?:@([a-z][a-z0-9_]{2,31}))?(?:\\s+[\\s\\S]*)?$")
    private val AT_BOT = Regex("^@([a-z][a-z0-9_]{2,31})(?:\\s+[\\s\\S]*)?$")

    fun looksLikeCiphertext(content: String): Boolean {
        val t = content.trim()
        if (t.isEmpty()) return false
        if (t.startsWith("SK:") || t.startsWith("ENC:")) return true
        if (t.contains("\"ciphertext\"") || t.contains("\"senderKey")) return true
        return false
    }

    fun parseSlash(text: String): SlashCommand? {
        val match = SLASH.matchEntire(text.trim()) ?: return null
        return SlashCommand(
            command = match.groupValues[1].lowercase(),
            targetUsername = match.groupValues[2].lowercase().ifBlank { null },
            arguments = text.trim().substringAfter(" ").trim().let { rest ->
                if (rest == text.trim()) "" else rest
            },
        )
    }

    fun shouldSendInbox(text: String, isDirectWithBot: Boolean, hasGroupBots: Boolean): Boolean {
        val cleaned = text.trim()
        if (cleaned.isEmpty() || looksLikeCiphertext(cleaned)) return false
        if (isDirectWithBot) return true
        if (!hasGroupBots) return false
        return parseSlash(cleaned) != null || AT_BOT.matches(cleaned)
    }
}
