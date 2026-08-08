package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import java.util.Calendar

internal enum class ChatSearchMode { KEYWORD, SEMANTIC }
internal enum class ChatSearchScope { ALL, TEXT, VOICE, TRANSLATION, STARRED, MEDIA, MENTIONS }
internal enum class ChatSearchWindow { ALL, TODAY, SEVEN_DAYS, THIRTY_DAYS }

internal data class ChatSearchDocument(
    val message: Message,
    val text: String?,
    val voiceTranscript: String?,
    val translations: List<String>
) {
    fun parts(scope: ChatSearchScope): List<String> = when (scope) {
        ChatSearchScope.ALL, ChatSearchScope.STARRED, ChatSearchScope.MENTIONS -> buildList {
            text?.takeIf(String::isNotBlank)?.let(::add)
            voiceTranscript?.takeIf(String::isNotBlank)?.let(::add)
            addAll(translations)
        }
        ChatSearchScope.TEXT -> listOfNotNull(text?.takeIf(String::isNotBlank))
        ChatSearchScope.VOICE -> listOfNotNull(voiceTranscript?.takeIf(String::isNotBlank))
        ChatSearchScope.TRANSLATION -> translations
        ChatSearchScope.MEDIA -> emptyList()
    }

    fun matchesMediaScope(): Boolean = when (message.type) {
        MessageType.IMAGE,
        MessageType.GIF,
        MessageType.VIDEO,
        MessageType.FILE,
        MessageType.STICKER,
        MessageType.LOCATION -> true
        else -> false
    }
}

/** Parses encrypted-local message content/meta once per message-list revision, not once per keystroke. */
internal fun buildChatSearchDocuments(messages: List<Message>): List<ChatSearchDocument> =
    messages.asSequence()
        .filter { it.type !in EXCLUDED_TYPES }
        .map { message ->
            val meta = message.parsedMeta()
            ChatSearchDocument(
                message = message,
                text = message.parsedContent().takeIf { message.type == MessageType.TEXT },
                voiceTranscript = meta.voiceTranscript,
                translations = meta.translations.values.filter(String::isNotBlank)
            )
        }
        .toList()

internal fun searchChatDocuments(
    documents: List<ChatSearchDocument>,
    query: String,
    scope: ChatSearchScope,
    window: ChatSearchWindow,
    currentUserId: String = "",
    now: Long = System.currentTimeMillis()
): List<Message> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank() && scope != ChatSearchScope.STARRED && scope != ChatSearchScope.MEDIA && scope != ChatSearchScope.MENTIONS) return emptyList()
    val tokens = normalizedQuery.split(WHITESPACE).filter(String::isNotBlank)
    val windowStart = searchWindowStart(window, now)
    return documents.asSequence()
        .filter { windowStart == null || it.message.timestamp >= windowStart }
        .filter { scope != ChatSearchScope.STARRED || it.message.starred }
        .filter { scope != ChatSearchScope.MEDIA || it.matchesMediaScope() }
        // 1.26：提到我（含 @所有人）
        .filter { scope != ChatSearchScope.MENTIONS || it.message.parsedMeta().mentions.any { m -> m == currentUserId || m == MentionPolicy.EVERYONE_ID } }
        .mapNotNull { document ->
            if (scope == ChatSearchScope.MEDIA) {
                if (normalizedQuery.isBlank()) {
                    document.message to 1
                } else {
                    // Prefer text/filename matches; otherwise match common type keywords.
                    val textScore = document.score(normalizedQuery, tokens, ChatSearchScope.ALL)
                    val typeHint = when (document.message.type) {
                        MessageType.IMAGE, MessageType.GIF -> "image gif 图片 图"
                        MessageType.VIDEO -> "video 视频"
                        MessageType.FILE -> "file 文件"
                        MessageType.STICKER -> "sticker 贴纸"
                        MessageType.LOCATION -> "location 位置 地点"
                        else -> ""
                    }.lowercase()
                    val typeScore = if (typeHint.contains(normalizedQuery) || tokens.any { typeHint.contains(it) }) 2 else 0
                    val score = maxOf(textScore, typeScore)
                    if (score <= 0) null else document.message to score
                }
            } else {
                val score = document.score(normalizedQuery, tokens, scope)
                if (score <= 0) null else document.message to score
            }
        }
        .sortedWith(compareByDescending<Pair<Message, Int>> { it.second }.thenByDescending { it.first.timestamp })
        .map { it.first }
        .toList()
}

internal fun semanticSearchCandidates(
    documents: List<ChatSearchDocument>,
    scope: ChatSearchScope,
    window: ChatSearchWindow,
    query: String = "",
    currentUserId: String = "",
    now: Long = System.currentTimeMillis()
): List<Message> {
    val windowStart = searchWindowStart(window, now)
    // 关键词预过滤：如果查询含关键词，优先保留含关键词的候选，减少上送给 AI 的无关消息
    val queryTokens = if (query.isBlank()) emptyList()
        else query.trim().lowercase().split(WHITESPACE).filter { it.length >= 2 }
    val filtered = documents.asSequence()
        .filter { windowStart == null || it.message.timestamp >= windowStart }
        .filter { scope != ChatSearchScope.STARRED || it.message.starred }
        .filter { scope != ChatSearchScope.MEDIA || it.matchesMediaScope() }
        // 1.26：提到我（含 @所有人）
        .filter { scope != ChatSearchScope.MENTIONS || it.message.parsedMeta().mentions.any { m -> m == currentUserId || m == MentionPolicy.EVERYONE_ID } }
        .filter {
            if (scope == ChatSearchScope.MEDIA) true
            else it.parts(scope).any(String::isNotBlank)
        }
        .toList()
    // 星标消息始终保留为候选（高价值）
    val starred = filtered.filter { it.message.starred }
    // 关键词命中优先（预过滤优化：减少 AI 无关上下文）
    val keywordHits = if (queryTokens.isEmpty()) emptyList()
        else filtered.filter { doc ->
            doc.parts(scope).any { part ->
                val lower = part.lowercase()
                queryTokens.any { lower.contains(it) }
            }
        }
    // 每发送者多样性：仅当存在 >2 个不同发送者时限制同一发送者占比，确保候选覆盖更多对话方
    val distinctSenders = filtered.map { it.message.senderId }.distinct().size
    val maxPerSender = if (distinctSenders <= 2) MAX_SEMANTIC_CANDIDATES
        else (MAX_SEMANTIC_CANDIDATES / distinctSenders * 2).coerceIn(10, MAX_SEMANTIC_CANDIDATES)
    val senderCounts = mutableMapOf<String, Int>()
    val diverse = (keywordHits + starred + filtered)
        .distinctBy { it.message.id }
        .filter { doc ->
            val senderId = doc.message.senderId
            val count = senderCounts[senderId] ?: 0
            if (count >= maxPerSender) false
            else { senderCounts[senderId] = count + 1; true }
        }
    return diverse
        .sortedBy { it.message.timestamp }
        .map(ChatSearchDocument::message)
        .takeLast(MAX_SEMANTIC_CANDIDATES)
}

private fun ChatSearchDocument.score(query: String, tokens: List<String>, scope: ChatSearchScope): Int {
    if ((scope == ChatSearchScope.STARRED || scope == ChatSearchScope.MENTIONS) && query.isBlank()) return 1
    if (query.isBlank()) return 0
    return parts(scope).maxOfOrNull { part ->
        val normalized = part.lowercase()
        var score = 0
        if (normalized.contains(query)) score += 12
        tokens.forEach { token -> if (normalized.contains(token)) score += 3 }
        if (normalized.startsWith(query)) score += 4
        score
    } ?: 0
}

private fun searchWindowStart(window: ChatSearchWindow, now: Long): Long? = when (window) {
    ChatSearchWindow.ALL -> null
    ChatSearchWindow.TODAY -> Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    ChatSearchWindow.SEVEN_DAYS -> now - SEVEN_DAYS_MS
    ChatSearchWindow.THIRTY_DAYS -> now - THIRTY_DAYS_MS
}

private val EXCLUDED_TYPES = setOf(MessageType.SK_DIST, MessageType.SYSTEM, MessageType.NUDGE)
private val WHITESPACE = Regex("\\s+")
private const val MAX_SEMANTIC_CANDIDATES = 100
