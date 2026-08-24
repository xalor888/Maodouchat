package com.maodouchat.bot

/**
 * 群成员互发仍走 Sender Key。只有用户主动打出的 /命令、@bot、以及与 bot 的私聊明文，
 * 才另送 bot-inbox。密文一律不当命令。
 */
object BotCommandPolicy {
    const val MAX_TEXT_CHARS = 4_000

    data class SlashCommand(
        val command: String,
        val targetUsername: String?,
        val arguments: String,
    )

    data class BotCommandItem(
        val botId: String,
        val username: String,
        val name: String,
        val command: String,
        val description: String,
    )

    private val SLASH = Regex("^/([a-z][a-z0-9_]{0,31})(?:@([a-z][a-z0-9_]{2,31}))?(?:\\s+[\\s\\S]*)?$")
    private val AT_BOT = Regex("^@([a-z][a-z0-9_]{2,31})(?:\\s+[\\s\\S]*)?$")

    fun isBotUserId(userId: String): Boolean = userId.startsWith("bot_")

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

    fun shouldOfferComposerMenu(draft: String): Boolean {
        val t = draft.trimStart()
        return t.startsWith("/") && !t.contains('\n') && t.length <= 64
    }

    fun composerMenuQuery(draft: String): String =
        draft.trimStart().removePrefix("/").substringBefore(" ").substringBefore("@").lowercase()

    fun filterCommands(items: List<BotCommandItem>, draft: String): List<BotCommandItem> {
        if (!shouldOfferComposerMenu(draft)) return emptyList()
        val q = composerMenuQuery(draft)
        if (q.isEmpty()) return items.take(20)
        return items.filter { it.command.startsWith(q) }.take(20)
    }

    fun insertCommand(item: BotCommandItem, multiBot: Boolean): String =
        if (multiBot) "/${item.command}@${item.username} " else "/${item.command} "

    fun shouldSendInbox(text: String, isDirectWithBot: Boolean, hasGroupBots: Boolean): Boolean {
        val cleaned = text.trim()
        if (cleaned.isEmpty() || looksLikeCiphertext(cleaned)) return false
        if (isDirectWithBot) return true
        if (!hasGroupBots) return false
        return parseSlash(cleaned) != null || AT_BOT.matches(cleaned)
    }
}
