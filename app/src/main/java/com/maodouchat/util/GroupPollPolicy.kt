package com.maodouchat.util

/**
 * 群玩法 B3 策略：群投票 / 群签到+排行 / 群接龙 / 群 PK。
 *
 * 本文件为纯本地策略层（与 GroupPlayPolicy.kt 职责一致但独立成文件，避免改动既有文件）：
 *  1. Markdown 快捷符（~vote / ~checkin / ~chain / ~pk）的构建与解析；
 *  2. 输入校验策略（标题/选项/内容长度、范围）；
 *  3. 聊天内共享文本的格式化（消息气泡展示用）。
 *
 * 数据持久化在服务端（PollRouting.kt / GroupCheckinRepository.kt），
 * 投票/签到为群内公开元数据，明文传输即可。
 */
object GroupPollPolicy {

    // ── 快捷符前缀（与服务端事件名无耦合，纯客户端契约）──
    const val VOTE_PREFIX = "~vote:"
    const val CHECKIN_PREFIX = "~checkin"
    const val CHAIN_PREFIX = "~chain:"
    const val PK_PREFIX = "~pk:"

    private fun esc(s: String): String = s.replace("|", "\u0001").replace("^", "\u0002")
    private fun unesc(s: String): String = s.replace("\u0001", "|").replace("\u0002", "^")

    // ── ~vote 快捷符 ──────────────────────────────────

    /** 构建投票快捷符：~vote:pollId|0,2 */
    fun buildVoteShortcut(pollId: String, optionIndexes: List<Int>): String =
        VOTE_PREFIX + pollId + "|" + optionIndexes.joinToString(",")

    /** 解析投票快捷符，返回 (pollId, optionIndexes)；非快捷符或格式非法返回 null。 */
    fun parseVoteShortcut(text: String): Pair<String, List<Int>>? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(VOTE_PREFIX)) return null
        val body = trimmed.removePrefix(VOTE_PREFIX)
        val pollId = body.substringBefore('|')
        val indexesText = body.substringAfter('|', "")
        if (pollId.isBlank() || indexesText.isBlank()) return null
        val indexes = indexesText.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it >= 0 }
            .distinct()
        if (indexes.isEmpty()) return null
        return pollId to indexes
    }

    /** 是否投票快捷符（消息气泡据此渲染可交互投票卡）。 */
    fun isVoteShortcut(text: String): Boolean = parseVoteShortcut(text) != null

    // ── ~checkin 快捷符 ───────────────────────────────

    /** 构建签到快捷符：~checkin（可选携带群 ID 便于服务端校验上下文）。 */
    fun buildCheckinShortcut(chatId: String = ""): String =
        if (chatId.isBlank()) CHECKIN_PREFIX else "$CHECKIN_PREFIX:$chatId"

    /** 解析签到快捷符，返回可选 chatId。 */
    fun parseCheckinShortcut(text: String): String? {
        val trimmed = text.trim()
        if (trimmed == CHECKIN_PREFIX) return null
        if (trimmed.startsWith("$CHECKIN_PREFIX:")) return trimmed.removePrefix("$CHECKIN_PREFIX:").trim().takeIf { it.isNotBlank() }
        return null
    }

    fun isCheckinShortcut(text: String): Boolean = parseCheckinShortcut(text) != null || text.trim() == CHECKIN_PREFIX

    // ── ~chain 快捷符 ────────────────────────────────

    /** 构建接龙快捷符：~chain:chainId|内容 */
    fun buildChainShortcut(chainId: String, content: String): String =
        CHAIN_PREFIX + chainId + "|" + esc(content)

    /** 解析接龙快捷符，返回 (chainId, content)。 */
    fun parseChainShortcut(text: String): Pair<String, String>? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(CHAIN_PREFIX)) return null
        val body = trimmed.removePrefix(CHAIN_PREFIX)
        val chainId = body.substringBefore('|')
        val content = unesc(body.substringAfter('|', ""))
        if (chainId.isBlank() || content.isBlank()) return null
        return chainId to content
    }

    fun isChainShortcut(text: String): Boolean = parseChainShortcut(text) != null

    // ── ~pk 快捷符 ───────────────────────────────────

    /** 构建 PK 快捷符：~pk:pkId|left（或 right） */
    fun buildPkShortcut(pkId: String, choice: String): String =
        PK_PREFIX + pkId + "|" + choice.lowercase().takeIf { it == "left" || it == "right" } ?: "left"

    /** 解析 PK 快捷符，返回 (pkId, choice)，choice ∈ {left, right}。 */
    fun parsePkShortcut(text: String): Pair<String, String>? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(PK_PREFIX)) return null
        val body = trimmed.removePrefix(PK_PREFIX)
        val pkId = body.substringBefore('|')
        val choice = body.substringAfter('|', "").trim().lowercase()
        if (pkId.isBlank() || (choice != "left" && choice != "right")) return null
        return pkId to choice
    }

    fun isPkShortcut(text: String): Boolean = parsePkShortcut(text) != null

    // ── 输入校验策略 ─────────────────────────────────

    fun isValidPollQuestion(question: String): Boolean =
        question.isNotBlank() && question.trim().length <= MAX_POLL_QUESTION_LENGTH

    fun isValidPollOptions(options: List<String>): Boolean {
        val opts = options.map { it.trim() }.filter { it.isNotBlank() }
        if (opts.size !in MIN_POLL_OPTIONS..MAX_POLL_OPTIONS) return false
        return opts.all { it.length <= MAX_POLL_OPTION_LENGTH } && opts.map { it.lowercase() }.distinct().size == opts.size
    }

    fun sanitizePollOptions(options: List<String>): List<String> =
        options.map { it.trim() }.filter { it.isNotBlank() }.map { it.take(MAX_POLL_OPTION_LENGTH) }

    fun isValidChainTitle(title: String): Boolean = title.isNotBlank() && title.trim().length <= 200
    fun isValidChainTopic(topic: String): Boolean = topic.length <= 500
    fun isValidChainContent(content: String): Boolean = content.isNotBlank() && content.trim().length <= 500
    fun isValidPkTitle(title: String): Boolean = title.isNotBlank() && title.trim().length <= 120

    // ── 聊天内共享文本格式化（消息气泡展示）────────────

    /** 投票创建后生成的分享文本（含快捷符，便于群友直接 ~vote 参与）。 */
    fun formatPollShare(pollId: String, question: String, options: List<String>, multi: Boolean): String =
        buildString {
            append("📊 群投票 · ${question.take(200)}\n")
            options.forEachIndexed { i, o -> append("${i + 1}. $o\n") }
            if (multi) append("（可多选）\n")
            append(buildVoteShortcut(pollId, listOf(0)))
        }

    /** 签到分享文本。 */
    fun formatCheckinShare(userLabel: String, streak: Int): String =
        "${CHECKIN_PREFIX} ${userLabel} 已签到 · 连续 $streak 天"

    /** 接龙分享文本。 */
    fun formatChainShare(chainId: String, title: String, topic: String, sequence: Int): String =
        "🔗 群接龙 · $title\n$topic\n第 $sequence 条：${buildChainShortcut(chainId, topic)}"

    /** PK 分享文本。 */
    fun formatPkShare(pkId: String, leftTitle: String, rightTitle: String): String =
        "⚔️ 群 PK\n${buildPkShortcut(pkId, "left")} · ${buildPkShortcut(pkId, "right")}\n$leftTitle vs $rightTitle"

    private const val MIN_POLL_OPTIONS = 2
    private const val MAX_POLL_OPTIONS = 12
    private const val MAX_POLL_QUESTION_LENGTH = 200
    private const val MAX_POLL_OPTION_LENGTH = 80
}
