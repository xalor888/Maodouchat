package com.maodouchat.ai

import com.maodouchat.data.model.Message

// 会话画像本地统计：消息量/活跃天数/时段分布/表情/高频主题词（CJK 二元组 + 拉丁词，
// 本地轻量统计，不引入 embedding）。
internal object AiProfileStats {
    internal fun computeStats(messages: List<Message>): AiConversationProfile.LocalStats {
        if (messages.isEmpty()) return AiConversationProfile.LocalStats()
        val days = messages.map { java.util.Calendar.getInstance().apply { timeInMillis = it.timestamp }.get(java.util.Calendar.DAY_OF_YEAR) }.toSet()
        var morning = 0; var afternoon = 0; var evening = 0; var night = 0; var emoji = 0
        val termCounts = HashMap<String, Int>()
        for (message in messages) {
            val hour = java.util.Calendar.getInstance().apply { timeInMillis = message.timestamp }.get(java.util.Calendar.HOUR_OF_DAY)
            when (hour) {
                in 5..11 -> morning++
                in 12..17 -> afternoon++
                in 18..23 -> evening++
                else -> night++
            }
            val text = message.parsedContent()
            emoji += EMOJI_PATTERN.findAll(text).count()
            collectTerms(text, termCounts)
        }
        val topTerms = termCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
            .take(MAX_TERMS)
        return AiConversationProfile.LocalStats(
            messageCount = messages.size,
            activeDays = days.size,
            morning = morning,
            afternoon = afternoon,
            evening = evening,
            night = night,
            emojiCount = emoji,
            topTerms = topTerms
        )
    }

    private fun collectTerms(text: String, counts: MutableMap<String, Int>) {
        val cleaned = text.lowercase().filterNot { it.isWhitespace() || it.isDigit() }
        val cjk = StringBuilder()
        cleaned.forEach { char ->
            if (char.code in 0x4E00..0x9FFF) cjk.append(char) else cjk.append(' ')
        }
        cjk.split(' ').forEach { segment ->
            if (segment.length >= 2) {
                for (index in 0 until segment.length - 1) {
                    val bigram = segment.substring(index, index + 2)
                    counts[bigram] = (counts[bigram] ?: 0) + 1
                }
            }
        }
        cleaned.split(profileWordSplitRegex).forEach { word ->
            if (word.length in 3..20 && STOP_WORDS.none { it == word }) {
                counts[word] = (counts[word] ?: 0) + 1
            }
        }
    }

    // 画像关键词切分：每次调用重复编译正则，提到对象级复用。
    private val profileWordSplitRegex = Regex("[^a-z0-9]+")
    private val EMOJI_PATTERN = Regex("""[\uD83C-\uDBFF\uDC00-\uDFFF\u2600-\u27BF\uFE0F]""")
    private val STOP_WORDS = setOf("the", "and", "for", "you", "your", "that", "this", "with", "have", "from", "are", "not", "was", "has", "but", "what", "all", "can", "out", "who", "when", "will", "just", "like", "about")

    private const val MAX_TERMS = 10
}
