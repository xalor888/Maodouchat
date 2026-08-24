package com.maodouchat.server.bot

/**
 * 用户主动发给 bot 的指令。群成员互发仍是 Sender Key，这里绝不把密文当命令。
 */
object BotCommandPolicy {
    const val MAX_TEXT_CHARS = 4_000
    const val MAX_COMMAND_CHARS = 32
    private val SLASH = Regex("^/([a-z][a-z0-9_]{0,31})(?:@([a-z][a-z0-9_]{2,31}))?(?:\\s+([\\s\\S]*))?$")
    private val AT_BOT = Regex("^@([a-z][a-z0-9_]{2,31})(?:\\s+([\\s\\S]*))?$")

    data class SlashCommand(
        val command: String,
        val targetUsername: String?,
        val arguments: String,
    )

    data class MentionCommand(
        val username: String,
        val text: String,
    )

    fun isBotUserId(userId: String): Boolean = userId.startsWith("bot_")

    fun looksLikeCiphertext(content: String): Boolean {
        val t = content.trim()
        if (t.isEmpty()) return false
        if (t.startsWith("SK:") || t.startsWith("ENC:")) return true
        if (t.contains("\"ciphertext\"") || t.contains("\"senderKey")) return true
        return false
    }

    fun sanitizeInboxText(raw: String): String? {
        val text = raw.trim().take(MAX_TEXT_CHARS)
        if (text.isEmpty()) return null
        if (looksLikeCiphertext(text)) return null
        return text
    }

    fun parseSlash(text: String): SlashCommand? {
        val match = SLASH.matchEntire(text.trim()) ?: return null
        val command = match.groupValues[1].lowercase()
        val username = match.groupValues[2].lowercase().ifBlank { null }
        val arguments = match.groupValues.getOrNull(3)?.trim().orEmpty()
        return SlashCommand(command = command, targetUsername = username, arguments = arguments)
    }

    fun parseMention(text: String): MentionCommand? {
        val match = AT_BOT.matchEntire(text.trim()) ?: return null
        return MentionCommand(
            username = match.groupValues[1].lowercase(),
            text = match.groupValues.getOrNull(2)?.trim().orEmpty(),
        )
    }

    fun shouldOfferComposerMenu(draft: String): Boolean {
        val t = draft.trimStart()
        return t.startsWith("/") && !t.contains('\n') && t.length <= 64
    }

    fun composerMenuQuery(draft: String): String =
        draft.trimStart().removePrefix("/").substringBefore(" ").substringBefore("@").lowercase()

    /**
     * 群：斜杠 / @bot；与 bot 的 1:1：整段明文都可进 inbox。
     * 绝不把密文当指令。
     */
    fun shouldAcceptInbox(text: String, isDirectWithBot: Boolean): Boolean {
        val cleaned = sanitizeInboxText(text) ?: return false
        if (isDirectWithBot) return true
        return parseSlash(cleaned) != null || parseMention(cleaned) != null
    }
}
