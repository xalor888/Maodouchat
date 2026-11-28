package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageReaction
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Calendar
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// Chat search restated by hand. Ground truth, read from the chat
// search model + search models + message meta/HTML-entity helpers:
//   - build: skip SK_DIST/SYSTEM/NUDGE; text is the unescaped body
//     before the last "<meta>" tag, only for TEXT; voice/translations
//     come from the meta JSON (mentions are not surfaced here).
//   - search: blank query only searches STARRED/MEDIA/MENTIONS;
//     window filters by timestamp; score is +12 full-query hit, +3
//     per token hit, +4 prefix hit, best part wins; MEDIA with a
//     query also scores type keywords (+2) and takes the max.
//   - semantic: keyword hits first, then starred, then the rest,
//     capped per sender when >2 senders, sorted by timestamp, last 100.
//   - window: TODAY is local midnight; SEVEN/THIRTY_DAYS subtract.
internal val SEARCH_JSON183: Json = Json { ignoreUnknownKeys = true }

internal fun JsonElement.asStringOrNullSearch183(): String? =
    if (this is JsonPrimitive) contentOrNull else null

// Only the three search-relevant meta fields; a broken meta block
// (bad JSON, non-object root) falls back to empty, like production.
internal data class MetaBits183(
    val mentions: List<String>,
    val voiceTranscript: String?,
    val translations: List<String>,
)

internal fun metaBitsOracle183(content: String): MetaBits183 {
    val idx = content.lastIndexOf("<meta>")
    if (idx < 0) return MetaBits183(emptyList(), null, emptyList())
    val json = content.substring(idx + "<meta>".length).substringBefore("</meta>")
    if (json.isBlank()) return MetaBits183(emptyList(), null, emptyList())
    return try {
        val element = SEARCH_JSON183.parseToJsonElement(json).jsonObject
        val mentions = (element["mentions"] as? JsonArray)
            ?.mapNotNull { it.asStringOrNullSearch183() } ?: emptyList()
        val voiceTranscript = element["voiceTranscript"]?.asStringOrNullSearch183()
        val translations = (element["translations"] as? JsonObject)
            ?.mapValues { (_, value) -> value.asStringOrNullSearch183().orEmpty() }
            ?.filterValues { it.isNotBlank() }
            ?.values?.toList().orEmpty()
        MetaBits183(mentions, voiceTranscript, translations)
    } catch (e: Throwable) {
        MetaBits183(emptyList(), null, emptyList())
    }
}

internal fun unescapeHtmlEntitiesOracle183(raw: String): String {
    if (raw.isEmpty() || raw.indexOf('&') < 0) return raw
    var out = raw
    out = Regex("&#(\\d{1,7});").replace(out) { match ->
        match.groupValues[1].toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { code ->
            String(intArrayOf(code), 0, 1)
        } ?: match.value
    }
    out = Regex("&#x([0-9a-fA-F]{1,6});", RegexOption.IGNORE_CASE).replace(out) { match ->
        match.groupValues[1].toIntOrNull(16)?.takeIf { it in 1..0x10FFFF }?.let { code ->
            String(intArrayOf(code), 0, 1)
        } ?: match.value
    }
    return out
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}

internal fun parsedContentOracle183(content: String): String =
    content.lastIndexOf("<meta>").let { idx ->
        val body = if (idx < 0) content else content.substring(0, idx)
        unescapeHtmlEntitiesOracle183(body)
    }

internal fun buildDocsOracle183(messages: List<Message>): List<ChatSearchDocument> =
    messages.asSequence()
        .filter { it.type !in setOf(MessageType.SK_DIST, MessageType.SYSTEM, MessageType.NUDGE) }
        .map { message ->
            val meta = metaBitsOracle183(message.content)
            ChatSearchDocument(
                message = message,
                text = parsedContentOracle183(message.content).takeIf { message.type == MessageType.TEXT },
                voiceTranscript = meta.voiceTranscript,
                translations = meta.translations,
            )
        }
        .toList()

internal fun docPartsOracle183(doc: ChatSearchDocument, scope: ChatSearchScope): List<String> = when (scope) {
    ChatSearchScope.ALL, ChatSearchScope.STARRED, ChatSearchScope.MENTIONS -> buildList {
        doc.text?.takeIf(String::isNotBlank)?.let(::add)
        doc.voiceTranscript?.takeIf(String::isNotBlank)?.let(::add)
        addAll(doc.translations)
    }
    ChatSearchScope.TEXT -> listOfNotNull(doc.text?.takeIf(String::isNotBlank))
    ChatSearchScope.VOICE -> listOfNotNull(doc.voiceTranscript?.takeIf(String::isNotBlank))
    ChatSearchScope.TRANSLATION -> doc.translations
    ChatSearchScope.MEDIA -> emptyList()
}

internal fun matchesMediaOracle183(doc: ChatSearchDocument): Boolean = when (doc.message.type) {
    MessageType.IMAGE, MessageType.GIF, MessageType.VIDEO,
    MessageType.FILE, MessageType.STICKER, MessageType.LOCATION -> true
    else -> false
}

internal fun docScoreOracle183(
    doc: ChatSearchDocument,
    query: String,
    tokens: List<String>,
    scope: ChatSearchScope,
): Int {
    if ((scope == ChatSearchScope.STARRED || scope == ChatSearchScope.MENTIONS) && query.isBlank()) return 1
    if (query.isBlank()) return 0
    return docPartsOracle183(doc, scope).maxOfOrNull { part ->
        val normalized = part.lowercase()
        var score = 0
        if (normalized.contains(query)) score += 12
        tokens.forEach { token -> if (normalized.contains(token)) score += 3 }
        if (normalized.startsWith(query)) score += 4
        score
    } ?: 0
}

internal fun windowStartOracle183(window: ChatSearchWindow, now: Long): Long? = when (window) {
    ChatSearchWindow.ALL -> null
    ChatSearchWindow.TODAY -> Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    ChatSearchWindow.SEVEN_DAYS -> now - 7L * 24L * 60L * 60L * 1_000L
    ChatSearchWindow.THIRTY_DAYS -> now - 30L * 24L * 60L * 60L * 1_000L
}

internal fun searchDocsOracle183(
    documents: List<ChatSearchDocument>,
    query: String,
    scope: ChatSearchScope,
    window: ChatSearchWindow,
    currentUserId: String = "",
    now: Long,
): List<Message> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank() && scope != ChatSearchScope.STARRED && scope != ChatSearchScope.MEDIA && scope != ChatSearchScope.MENTIONS) return emptyList()
    val tokens = normalizedQuery.split(Regex("\\s+")).filter(String::isNotBlank)
    val windowStart = windowStartOracle183(window, now)
    return documents.asSequence()
        .filter { windowStart == null || it.message.timestamp >= windowStart }
        .filter { scope != ChatSearchScope.STARRED || it.message.starred }
        .filter { scope != ChatSearchScope.MEDIA || matchesMediaOracle183(it) }
        .filter { scope != ChatSearchScope.MENTIONS || metaBitsOracle183(it.message.content).mentions.any { m -> m == currentUserId || m == "__everyone__" } }
        .mapNotNull { document ->
            if (scope == ChatSearchScope.MEDIA) {
                if (normalizedQuery.isBlank()) {
                    document.message to 1
                } else {
                    val textScore = docScoreOracle183(document, normalizedQuery, tokens, ChatSearchScope.ALL)
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
                val score = docScoreOracle183(document, normalizedQuery, tokens, scope)
                if (score <= 0) null else document.message to score
            }
        }
        .sortedWith(compareByDescending<Pair<Message, Int>> { it.second }.thenByDescending { it.first.timestamp })
        .map { it.first }
        .toList()
}

internal fun semanticCandidatesOracle183(
    documents: List<ChatSearchDocument>,
    scope: ChatSearchScope,
    window: ChatSearchWindow,
    query: String = "",
    currentUserId: String = "",
    now: Long,
): List<Message> {
    val windowStart = windowStartOracle183(window, now)
    val queryTokens = if (query.isBlank()) emptyList()
        else query.trim().lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
    val filtered = documents.asSequence()
        .filter { windowStart == null || it.message.timestamp >= windowStart }
        .filter { scope != ChatSearchScope.STARRED || it.message.starred }
        .filter { scope != ChatSearchScope.MEDIA || matchesMediaOracle183(it) }
        .filter { scope != ChatSearchScope.MENTIONS || metaBitsOracle183(it.message.content).mentions.any { m -> m == currentUserId || m == "__everyone__" } }
        .filter {
            if (scope == ChatSearchScope.MEDIA) true
            else docPartsOracle183(it, scope).any(String::isNotBlank)
        }
        .toList()
    val starred = filtered.filter { it.message.starred }
    val keywordHits = if (queryTokens.isEmpty()) emptyList()
        else filtered.filter { doc ->
            docPartsOracle183(doc, scope).any { part ->
                val lower = part.lowercase()
                queryTokens.any { lower.contains(it) }
            }
        }
    val distinctSenders = filtered.map { it.message.senderId }.distinct().size
    val maxPerSender = if (distinctSenders <= 2) 100
        else (100 / distinctSenders * 2).coerceIn(10, 100)
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
        .takeLast(100)
}

internal fun escJsonSearch183(s: String): String {
    val sb = StringBuilder()
    sb.append("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u0008' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> {
                if (ch.code < 0x20 || ch.code in 0xD800..0xDFFF) {
                    sb.append("\\u")
                    val v = ch.code
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append("\"")
    return sb.toString()
}

internal fun messageWireJsonSearch183(m: Message): String {
    val reactions = m.reactions.joinToString(",") { r ->
        "{\"userId\":${escJsonSearch183(r.userId)},\"emoji\":${escJsonSearch183(r.emoji)},\"reactedAt\":${r.reactedAt}}"
    }
    val edited = m.editedAt?.toString() ?: "null"
    val expires = m.expiresAt?.toString() ?: "null"
    return "{\"id\":${escJsonSearch183(m.id)},\"chatId\":${escJsonSearch183(m.chatId)}," +
        "\"senderId\":${escJsonSearch183(m.senderId)},\"content\":${escJsonSearch183(m.content)}," +
        "\"type\":${escJsonSearch183(m.type.name)},\"timestamp\":${m.timestamp}," +
        "\"status\":${escJsonSearch183(m.status.name)},\"editedAt\":$edited," +
        "\"starred\":${m.starred},\"reactions\":[$reactions],\"expiresAt\":$expires," +
        "\"sealedSender\":${m.sealedSender}}"
}

internal fun documentWireJson183(d: ChatSearchDocument): String {
    val text = d.text?.let { escJsonSearch183(it) } ?: "null"
    val voice = d.voiceTranscript?.let { escJsonSearch183(it) } ?: "null"
    val translations = d.translations.joinToString(",") { escJsonSearch183(it) }
    return "{\"message\":${messageWireJsonSearch183(d.message)},\"text\":$text," +
        "\"voiceTranscript\":$voice,\"translations\":[$translations]}"
}

// ---------- production ----------

internal fun buildProduction183(messages: List<Message>): List<ChatSearchDocument> =
    buildChatSearchDocuments(messages)

internal fun searchProduction183(
    documents: List<ChatSearchDocument>,
    query: String,
    scope: ChatSearchScope,
    window: ChatSearchWindow,
    currentUserId: String,
    now: Long,
): List<Message> = searchChatDocuments(documents, query, scope, window, currentUserId, now)

internal fun semanticProduction183(
    documents: List<ChatSearchDocument>,
    scope: ChatSearchScope,
    window: ChatSearchWindow,
    query: String,
    currentUserId: String,
    now: Long,
): List<Message> = semanticSearchCandidates(documents, scope, window, query, currentUserId, now)

internal fun windowStartProduction183(window: ChatSearchWindow, now: Long): Long? =
    searchWindowStart(window, now)

// ---------- generators ----------

internal val SEARCH_META_JSON183: List<String> = listOf(
    "{\"mentions\":[\"alice\"],\"voiceTranscript\":\"hello world\",\"translations\":{\"en\":\"hello\"}}",
    "{\"mentions\":[\"alice\",\"__everyone__\"],\"translations\":{\"en\":\"\",\"fr\":\"bonjour\"}}",
    "{\"mentions\":[42,true,null],\"voiceTranscript\":123,\"translations\":{\"en\":456}}",
    "{\"mentions\":\"alice\"}",
    "{\"translations\":\"en\"}",
    "{}",
    "{\"mentions\":[],\"voiceTranscript\":null,\"translations\":{}}",
    "{",
    "{\"mentions\":}",
    "{\"a\":01}",
    "{\"a\":NaN}",
    "{\"voiceTranscript\":\"caf\\u00e9\"}",
    "{\"mentions\":[\"bob\"],\"voiceTranscript\":\"  spaced  \",\"translations\":{\"zh\":\"你好\",\"en\":\"  \"}}",
)

internal val SEARCH_BODIES183: List<String> = listOf(
    "hello world", "Hello World", "HELLO", "你好世界", "meeting notes",
    "voice memo", "image file", "video clip", "sticker pack",
    "a &amp; b", "a &lt;tag&gt; b", "&#65;&#x42;", "&#0; &#xD800; &#x110000; &#9999999;",
    "&nbsp;&quot;&#39;&apos;&amp;", "&unknown; &amp;lt;",
    "prefix <meta> literal in body",
    "",
    " ",
    "x",
    "hello hello hello",
    "the quick brown fox jumps over the lazy dog",
    "café résumé",
    "😀 emoji text 😀",
)

internal val SEARCH_TYPES183: List<MessageType> = MessageType.entries

internal fun randomMessageSearch183(rnd: Random): Message {
    val body = SEARCH_BODIES183.random(rnd)
    val withMeta = when (rnd.nextInt(10)) {
        in 0..4 -> body + "<meta>" + SEARCH_META_JSON183.random(rnd) + "</meta>"
        5 -> body + "<meta>" + SEARCH_META_JSON183.random(rnd)
        6 -> body + "<meta>" + SEARCH_META_JSON183.random(rnd) + "</meta> trailing <meta>{\"mentions\":[\"carol\"]}</meta>"
        7 -> "<meta>" + SEARCH_META_JSON183.random(rnd) + "</meta>" + body
        else -> body
    }
    return Message(
        id = "m${rnd.nextInt(0, 8)}",
        chatId = "c1",
        senderId = listOf("alice", "bob", "carol", "dave").random(rnd),
        content = withMeta,
        type = SEARCH_TYPES183.random(rnd),
        timestamp = rnd.nextLong(0L, 200000L),
        status = MessageStatus.entries.random(rnd),
        editedAt = null,
        starred = rnd.nextBoolean(),
        reactions = emptyList(),
        expiresAt = null,
        sealedSender = false,
    )
}

internal fun randomDocument183(rnd: Random): ChatSearchDocument {
    val message = randomMessageSearch183(rnd)
    val text = when (rnd.nextInt(5)) {
        0 -> null
        1 -> ""
        2 -> "   "
        else -> SEARCH_BODIES183.random(rnd)
    }
    val voice = when (rnd.nextInt(5)) {
        0 -> null
        1 -> ""
        else -> listOf("hello world", "voice transcript here", "meeting", "  ").random(rnd)
    }
    val translations = when (rnd.nextInt(4)) {
        0 -> emptyList()
        1 -> listOf("")
        2 -> listOf("hello", "bonjour")
        else -> listOf("你好", "world", "")
    }
    return ChatSearchDocument(message, text, voice, translations)
}

internal data class SearchCase183(
    val documents: List<ChatSearchDocument>,
    val query: String,
    val scope: ChatSearchScope,
    val window: ChatSearchWindow,
    val currentUserId: String,
    val now: Long,
)

internal val SEARCH_QUERIES183: List<String> = listOf(
    "", " ", "hello", "HELLO", "hello world", "world", "你好", "café", "CAFÉ",
    "image", "video", "file", "sticker", "location", "图片", "视频",
    "  hello  ", "hello  world", "x", "emoji", "😀", "meeting notes",
    "alice", "bob", "voice", "transcript", "bonjour", "the", "a",
)

internal fun randomSearchCase183(rnd: Random): SearchCase183 {
    val n = rnd.nextInt(0, 8)
    return SearchCase183(
        documents = List(n) { randomDocument183(rnd) },
        query = SEARCH_QUERIES183.random(rnd),
        scope = ChatSearchScope.entries.random(rnd),
        window = ChatSearchWindow.entries.random(rnd),
        currentUserId = listOf("", "alice", "bob", "carol").random(rnd),
        now = rnd.nextLong(0L, 200000L),
    )
}

internal fun searchCaseWireJson183(c: SearchCase183): String {
    val docs = c.documents.joinToString(",") { documentWireJson183(it) }
    return "{\"documents\":[$docs],\"query\":${escJsonSearch183(c.query)}," +
        "\"scope\":${escJsonSearch183(c.scope.name)},\"window\":${escJsonSearch183(c.window.name)}," +
        "\"currentUserId\":${escJsonSearch183(c.currentUserId)},\"now\":${c.now}}"
}

internal fun randomBuildMessages183(rnd: Random): List<Message> {
    val n = rnd.nextInt(0, 6)
    return List(n) { randomMessageSearch183(rnd) }
}

internal fun buildMessagesWireJson183(messages: List<Message>): String =
    "[" + messages.joinToString(",") { messageWireJsonSearch183(it) } + "]"

internal fun msgSearch183(
    content: String,
    type: MessageType = MessageType.TEXT,
    timestamp: Long = 1000L,
    starred: Boolean = false,
    senderId: String = "alice",
    id: String = "m1",
): Message = Message(
    id = id, chatId = "c1", senderId = senderId, content = content,
    type = type, timestamp = timestamp, status = MessageStatus.SENT,
)

internal fun docSearch183(
    content: String,
    type: MessageType = MessageType.TEXT,
    timestamp: Long = 1000L,
    starred: Boolean = false,
    senderId: String = "alice",
    id: String = "m1",
    text: String? = content,
    voiceTranscript: String? = null,
    translations: List<String> = emptyList(),
): ChatSearchDocument = ChatSearchDocument(
    msgSearch183(content, type, timestamp, starred, senderId, id),
    text, voiceTranscript, translations,
)

class ChatSearchFuzzTest {

    private fun checkBuild183(messages: List<Message>) {
        assertEquals(
            buildDocsOracle183(messages),
            buildProduction183(messages),
            "build($messages)",
        )
    }

    private fun checkSearch183(c: SearchCase183) {
        assertEquals(
            searchDocsOracle183(c.documents, c.query, c.scope, c.window, c.currentUserId, c.now)
                .map { it.id },
            searchProduction183(c.documents, c.query, c.scope, c.window, c.currentUserId, c.now)
                .map { it.id },
            "search($c)",
        )
    }

    private fun checkSemantic183(c: SearchCase183) {
        assertEquals(
            semanticCandidatesOracle183(c.documents, c.scope, c.window, c.query, c.currentUserId, c.now)
                .map { it.id },
            semanticProduction183(c.documents, c.scope, c.window, c.query, c.currentUserId, c.now)
                .map { it.id },
            "semantic($c)",
        )
    }

    private fun checkWindow183(window: ChatSearchWindow, now: Long) {
        assertEquals(
            windowStartOracle183(window, now),
            windowStartProduction183(window, now),
            "window($window, $now)",
        )
    }

    @Test
    fun pinnedChatSearch() {
        // Meta tag parsing: last tag wins, missing close tag, bad JSON.
        checkBuild183(
            listOf(
                msgSearch183("hello<meta>{\"voiceTranscript\":\"vt\",\"translations\":{\"en\":\"hi\"}}</meta>"),
                msgSearch183("plain text"),
                msgSearch183("a <meta> literal <meta>{\"mentions\":[\"bob\"]}</meta>"),
                msgSearch183("x<meta>not json</meta>"),
                msgSearch183("x<meta>{\"voiceTranscript\":\"open\"}"),
                msgSearch183("x<meta></meta>"),
                msgSearch183("x<meta>123</meta>"),
                msgSearch183("x<meta>[1,2]</meta>"),
                msgSearch183("x", type = MessageType.IMAGE),
                msgSearch183("x", type = MessageType.SK_DIST),
                msgSearch183("x", type = MessageType.SYSTEM),
                msgSearch183("x", type = MessageType.NUDGE),
                msgSearch183("a &amp; b &#65;&#x42; &lt;x&gt;"),
            )
        )
        // Entity decoding edges.
        checkBuild183(
            listOf(
                msgSearch183("&#0; &#xD800; &#x110000; &#9999999;"),
                msgSearch183("&nbsp;&quot;&#39;&apos;&amp;&lt;&gt;"),
                msgSearch183("&#65;&#x41;&#X41;&#x4a;"),
                msgSearch183("&unknown; &amp;lt; &amp;amp;"),
            )
        )
        // Search: scoring, ordering, scope gates.
        val docs = listOf(
            docSearch183("hello world", timestamp = 100L),
            docSearch183("say hello", timestamp = 200L),
            docSearch183("hello", timestamp = 50L, starred = true),
            docSearch183("image file", type = MessageType.IMAGE, timestamp = 300L),
            docSearch183("video clip", type = MessageType.VIDEO, timestamp = 400L, text = null),
            docSearch183("unrelated", timestamp = 500L),
        )
        for (scope in ChatSearchScope.entries) {
            checkSearch183(SearchCase183(docs, "hello", scope, ChatSearchWindow.ALL, "", 1000L))
            checkSearch183(SearchCase183(docs, "", scope, ChatSearchWindow.ALL, "", 1000L))
            checkSearch183(SearchCase183(docs, "image", scope, ChatSearchWindow.ALL, "", 1000L))
        }
        checkSearch183(SearchCase183(docs, "hello", ChatSearchScope.ALL, ChatSearchWindow.SEVEN_DAYS, "", 1000L))
        checkSearch183(SearchCase183(docs, "hello", ChatSearchScope.ALL, ChatSearchWindow.THIRTY_DAYS, "", 8L * 24L * 60L * 60L * 1000L))
        // Window boundaries: timestamp exactly at the window start.
        checkWindow183(ChatSearchWindow.ALL, 1000L)
        checkWindow183(ChatSearchWindow.SEVEN_DAYS, 8L * 24L * 60L * 60L * 1000L)
        checkWindow183(ChatSearchWindow.THIRTY_DAYS, 31L * 24L * 60L * 60L * 1000L)
        checkWindow183(ChatSearchWindow.TODAY, 1700000000000L)
        checkWindow183(ChatSearchWindow.TODAY, 0L)
        // Mentions scope with meta mentions.
        val mentionDocs = listOf(
            docSearch183("hi<meta>{\"mentions\":[\"alice\"]}</meta>"),
            docSearch183("hi<meta>{\"mentions\":[\"__everyone__\"]}</meta>"),
            docSearch183("hi<meta>{\"mentions\":[\"bob\"]}</meta>"),
            docSearch183("hi"),
        )
        checkSearch183(SearchCase183(mentionDocs, "", ChatSearchScope.MENTIONS, ChatSearchWindow.ALL, "alice", 1000L))
        checkSearch183(SearchCase183(mentionDocs, "hi", ChatSearchScope.MENTIONS, ChatSearchWindow.ALL, "bob", 1000L))
        checkSearch183(SearchCase183(mentionDocs, "", ChatSearchScope.STARRED, ChatSearchWindow.ALL, "", 1000L))
        // Semantic: keyword hits before starred before the rest.
        val semDocs = listOf(
            docSearch183("hello world", timestamp = 100L, senderId = "alice", id = "m1"),
            docSearch183("hello again", timestamp = 200L, senderId = "alice", id = "m2", starred = true),
            docSearch183("other", timestamp = 300L, senderId = "bob", id = "m3"),
            docSearch183("hello", timestamp = 400L, senderId = "carol", id = "m4"),
        )
        checkSemantic183(SearchCase183(semDocs, "hello", ChatSearchScope.ALL, ChatSearchWindow.ALL, "", 1000L))
        checkSemantic183(SearchCase183(semDocs, "", ChatSearchScope.ALL, ChatSearchWindow.ALL, "", 1000L))
        checkSemantic183(SearchCase183(semDocs, "hello", ChatSearchScope.STARRED, ChatSearchWindow.ALL, "", 1000L))
    }

    @Test
    fun randomChatSearchMatchesProduction() {
        val rnd = Random(183003)
        repeat(1500) { checkBuild183(List(rnd.nextInt(0, 6)) { randomMessageSearch183(rnd) }) }
        repeat(1500) { checkSearch183(randomSearchCase183(rnd)) }
        repeat(1000) { checkSemantic183(randomSearchCase183(rnd)) }
        repeat(500) {
            checkWindow183(ChatSearchWindow.entries.random(rnd), rnd.nextLong(0L, 2000000000000L))
        }
    }

    @Test
    fun sweepChatSearchBoundaries() {
        // Every scope x window on a fixed document set, several queries.
        val docs = listOf(
            docSearch183("hello world", timestamp = 100L, senderId = "alice", id = "m1"),
            docSearch183("hello", timestamp = 200L, senderId = "bob", id = "m2", starred = true),
            docSearch183("image file", type = MessageType.IMAGE, timestamp = 300L, senderId = "carol", id = "m3"),
            docSearch183("voice note", type = MessageType.VOICE, timestamp = 400L, senderId = "alice", id = "m4",
                voiceTranscript = "hello transcript"),
            docSearch183("translated", timestamp = 500L, senderId = "bob", id = "m5",
                translations = listOf("hello translation")),
        )
        for (scope in ChatSearchScope.entries) {
            for (window in ChatSearchWindow.entries) {
                for (query in listOf("", "hello", "image", "voice", "transcript", "translation")) {
                    checkSearch183(SearchCase183(docs, query, scope, window, "alice", 1000L))
                    checkSemantic183(SearchCase183(docs, query, scope, window, "alice", 1000L))
                }
            }
        }
        // Per-sender cap: 3 senders, 4 docs each, distinct ids.
        val many = (0 until 12).map { i ->
            docSearch183("hello $i", timestamp = 100L + i, senderId = listOf("a", "b", "c")[i % 3], id = "m$i")
        }
        checkSemantic183(SearchCase183(many, "hello", ChatSearchScope.ALL, ChatSearchWindow.ALL, "", 1000L))
        checkSemantic183(SearchCase183(many, "", ChatSearchScope.ALL, ChatSearchWindow.ALL, "", 1000L))
        // 101 docs: takeLast(100) keeps the newest 100 by timestamp.
        val overflow = (0 until 101).map { i ->
            docSearch183("hello $i", timestamp = i.toLong(), senderId = "alice", id = "n$i")
        }
        checkSemantic183(SearchCase183(overflow, "", ChatSearchScope.ALL, ChatSearchWindow.ALL, "", 1000L))
    }
}
