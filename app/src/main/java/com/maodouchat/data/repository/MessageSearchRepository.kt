package com.maodouchat.data.repository

import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.MessageSearchDocumentEntity
import com.maodouchat.data.local.entity.MessageSearchFingerprint
import com.maodouchat.data.local.entity.MessageSearchMatchRow
import com.maodouchat.data.local.entity.MessageSearchTokenEntity
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.semanticSearchText
import com.maodouchat.ui.screen.chatlist.ChatListPreviewPolicy
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

class MessageSearchRepository(private val database: AppDatabase) {
    private val messageDao = database.messageDao()
    private val searchDao = database.messageSearchDao()
    private val secretChatDao = database.secretChatDao()

    /**
     * 全量重建搜索索引（8.31 性能优化 F18 的快速路径）：
     * 若索引已非空且文档数与可搜索消息数偏差在阈值内（增量索引在解密/编辑时已维护），
     * 直接跳过全量重建——避免每次打开全局搜索都全表扫描 + 全量比对。
     */
    suspend fun refreshIndexIfStale(): Int {
        val docCount = searchDao.countDocuments()
        // 8.48 修复：用「可搜索且正文非空」的消息数做基准——空白/密文消息 indexMessage
        // 时 deleteDocument 不产生文档，用全部可搜索消息数会导致 msgCount 恒 > docCount，
        // 每次打开全局搜索都误判漂移触发全量重建
        val msgCount = messageDao.countSearchableWithContent()
        // 8.49 修复：全库都无可索引内容（如新设备上全部消息仍是 backlog 密文）时直接短路，
        // 否则 docCount==0 分支会让每次打开全局搜索都触发全表游标扫描
        if (msgCount == 0 && docCount == 0) return 0
        if (docCount > 0) {
            val drift = kotlin.math.abs(msgCount - docCount)
            if (drift < (msgCount.toDouble() * STALE_DRIFT_RATIO).toInt().coerceAtLeast(MIN_STALE_DRIFT)) {
                // v25→v26 initialized every existing document as TEXT. The counts still match,
                // so also verify type metadata before taking the fast path.
                if (!searchDao.hasMessageTypeMismatch()) return 0
            }
        }
        return refreshIndex()
    }

    /**
     * 全量重建搜索索引。
     * 关键约束：必须覆盖全部可搜索消息，不能因 LIMIT 截断而把历史消息的索引当成孤儿删除
     * （旧实现用 getSearchableMessages(limit=5000) 取窗口，导致超出窗口的旧消息索引在每次
     * refresh 时被静默清除，全局搜索丢历史）。
     * 采用「完整 id 集合 + 分批建索引」同时避免 OOM。
     */
    suspend fun refreshIndex(): Int {
        // 删除已不存在/不可搜索消息的孤儿索引：SQL 子查询批量清理，
        // 不再把完整消息 ID 集合载入内存（大库 OOM 风险）。
        searchDao.deleteDocumentsNotInSearchableTypes(SEARCHABLE_TYPES.map { it.name })

        // 分批建索引，避免一次性把全部实体载入内存（超大历史 OOM）。
        // 游标分页：以上一批最后一条的 (timestamp, id) 为游标，稳定且不受并发插入影响。
        var changed = 0
        var lastTs = -1L
        var lastId = ""
        val secretChatIds = secretChatDao.listSecretChatIds().toSet()
        val pageSize = 500
        while (true) {
            val batch = messageDao.getSearchableMessagesAfterCursor(lastTs, lastId, pageSize)
            if (batch.isEmpty()) break
            val batchFingerprints = searchDao.getFingerprintsForIds(batch.map { it.id })
                .associateBy { it.messageId }
            batch.forEach { entity ->
                if (indexMessage(
                        entity.toDomain(),
                        knownFingerprint = batchFingerprints[entity.id],
                        secretChatIds = secretChatIds
                    )
                ) changed++
            }
            val last = batch.last()
            lastTs = last.timestamp
            lastId = last.id
        }
        return changed
    }

    /**
     * Incremental index after local plaintext decrypt / edit so keyword search
     * does not wait for the next full [refreshIndex] (open global search).
     * Skips wire envelopes and non-searchable types; blank body deletes stale docs.
     */
    suspend fun indexMessage(
        message: Message,
        knownFingerprint: MessageSearchFingerprint? = null,
        secretChatIds: Set<String>? = null
    ): Boolean {
        // 密聊明文永不进全局搜索：任何入口都先删除该会话已有索引，再拒绝写入。
        val isSecretChat = secretChatIds?.contains(message.chatId)
            ?: (message.chatId.isNotBlank() && secretChatDao.isSecret(message.chatId))
        if (isSecretChat) {
            searchDao.deleteDocument(message.id)
            return knownFingerprint != null
        }
        // REVOKED/SK_DIST must drop any prior index even if the caller only re-indexes.
        if (message.type !in SEARCHABLE_TYPES) {
            if (message.type == MessageType.REVOKED || message.type == MessageType.SK_DIST) {
                searchDao.deleteDocument(message.id)
                return knownFingerprint != null
            }
            return false
        }
        val text = message.semanticSearchText(maxLength = MAX_DOCUMENT_CHARS)
        if (text.isBlank()) {
            searchDao.deleteDocument(message.id)
            return knownFingerprint != null
        }
        // Never index ciphertext / multi-device JSON as searchable body.
        if (ChatListPreviewPolicy.looksLikeWireEnvelope(text)) return false
        val hash = contentHash(message.chatId, message.senderId, message.timestamp, text)
        val previous = knownFingerprint ?: searchDao.getFingerprint(message.id)
        if (previous?.contentHash == hash && previous.messageType == message.type.name) return false
        val document = MessageSearchDocumentEntity(
            messageId = message.id,
            chatId = message.chatId,
            senderId = message.senderId,
            searchableText = text,
            contentHash = hash,
            timestamp = message.timestamp,
            indexedAt = System.currentTimeMillis(),
            messageType = message.type.name
        )
        val tokens = MessageSearchTokenizer.tokens(text).map { token ->
            MessageSearchTokenEntity(message.id, token, message.chatId, message.timestamp)
        }
        searchDao.replaceDocument(document, tokens)
        return true
    }

    suspend fun search(query: String, limit: Int = 80): List<MessageSearchDocumentEntity> {
        val tokens = MessageSearchTokenizer.tokens(query).take(MAX_QUERY_TOKENS)
        if (tokens.isEmpty()) return emptyList()
        return searchDao.search(tokens, limit.coerceIn(1, 100)).map(::toDocument)
    }

    /** 按消息类型过滤的搜索；[types] 为空表示不过滤。 */
    suspend fun searchByTypes(
        query: String,
        types: List<String>,
        limit: Int = 80
    ): List<MessageSearchDocumentEntity> {
        if (types.isEmpty()) return search(query, limit)
        val tokens = MessageSearchTokenizer.tokens(query).take(MAX_QUERY_TOKENS)
        if (tokens.isEmpty()) return emptyList()
        return searchDao.searchByTypes(tokens, types, limit.coerceIn(1, 100)).map(::toDocument)
    }

    private fun toDocument(row: MessageSearchMatchRow): MessageSearchDocumentEntity {
        return MessageSearchDocumentEntity(
            messageId = row.messageId,
            chatId = row.chatId,
            senderId = row.senderId,
            searchableText = row.searchableText,
            contentHash = row.contentHash,
            timestamp = row.timestamp,
            indexedAt = row.indexedAt,
            messageType = row.messageType
        )
    }

    private fun contentHash(chatId: String, senderId: String, timestamp: Long, text: String): String {
        val bytes = "$chatId\u0000$senderId\u0000$timestamp\u0000$text".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        /** 文档数与消息数偏差超过此比例或最小绝对值即触发全量重建（8.31 F18）。 */
        const val STALE_DRIFT_RATIO = 0.1
        const val MIN_STALE_DRIFT = 50
        const val MAX_DOCUMENT_CHARS = 10_000
        const val MAX_QUERY_TOKENS = 48
        val SEARCHABLE_TYPES = setOf(
            MessageType.TEXT,
            MessageType.MARKDOWN,
            MessageType.VOICE,
            MessageType.LOCATION,
            MessageType.NUDGE,
            MessageType.IMAGE,
            MessageType.GIF,
            MessageType.STICKER,
            MessageType.VIDEO,
            MessageType.FILE
        )
    }
}

object MessageSearchTokenizer {
    fun tokens(value: String): List<String> {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .take(4_000)
        val result = LinkedHashSet<String>()
        val latin = StringBuilder()
        val han = StringBuilder()

        fun flushLatin() {
            if (latin.isEmpty()) return
            val word = latin.toString().take(40)
            result += word
            if (word.length > 3) {
                for (length in 3..minOf(8, word.length - 1)) result += word.take(length)
            }
            latin.clear()
        }

        fun flushHan() {
            if (han.isEmpty()) return
            val run = han.toString()
            run.forEach { result += it.toString() }
            for (size in 2..3) {
                if (run.length >= size) {
                    for (index in 0..run.length - size) result += run.substring(index, index + size)
                }
            }
            han.clear()
        }

        normalized.forEach { char ->
            when {
                isHan(char) -> {
                    flushLatin()
                    han.append(char)
                }
                char.isLetterOrDigit() -> {
                    flushHan()
                    latin.append(char)
                }
                else -> {
                    flushLatin()
                    flushHan()
                }
            }
            if (result.size >= 200) return@forEach
        }
        flushLatin()
        flushHan()
        return result.take(200)
    }

    private fun isHan(char: Char): Boolean {
        val block = Character.UnicodeBlock.of(char) ?: return false
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
    }
}
