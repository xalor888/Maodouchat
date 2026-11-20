package com.maodouchat.core.model

/**
 * bot 身份判定与输入框斜杠菜单。收件箱判定已迁入 :domain:groups 的 BotInboxPolicy。
 */
object BotCommandPolicy {
    const val MAX_TEXT_CHARS = 4_000

    fun isBotUserId(userId: String): Boolean = userId.startsWith("bot_")

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
}
