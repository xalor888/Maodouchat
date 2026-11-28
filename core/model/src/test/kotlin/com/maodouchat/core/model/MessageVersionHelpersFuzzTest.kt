package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// Version-merge helpers restated by hand. Ground truth, read from
// MessageVersionMerge.kt:
//   - choose: REVOKED current beats any non-REVOKED candidate; a
//     REVOKED candidate always wins; otherwise the newer editedAt
//     wins (null counts as Long.MIN_VALUE), the readable side keeps
//     the content, and the newer revision owns editedAt/star/reactions
//     while status is the higher of the two (FAILED never wins).
//   - equal revision: candidate owns star/reactions and editedAt
//     (falling back to current when candidate has none), content
//     from the readable side, status higher of the two.
//   - preferReadableContent: plaintext beats ciphertext/failure,
//     ciphertext/failure loses to plaintext, both failures keep
//     current, anything else takes candidate.
internal fun deliveryRankOracle183(status: MessageStatus): Int = when (status) {
    MessageStatus.SENDING -> 0
    MessageStatus.SENT -> 1
    MessageStatus.DELIVERED -> 2
    MessageStatus.READ -> 3
    MessageStatus.FAILED -> -1
}

internal fun higherDeliveryStatusOracle183(a: MessageStatus, b: MessageStatus): MessageStatus {
    if (a == MessageStatus.FAILED) return b
    if (b == MessageStatus.FAILED) return a
    return if (deliveryRankOracle183(a) >= deliveryRankOracle183(b)) a else b
}

internal fun isSignalWireEnvelopeOracle183(content: String): Boolean {
    val t = content.trimStart()
    if (!t.startsWith("{") && !t.startsWith("[{")) return false
    if (t.contains("signal-")) {
        return t.contains("\"algorithm\"") || t.contains("\"distributionMessage\"") ||
            t.contains("\"ciphertext\"") || t.contains("\"senderKey")
    }
    val hasCiphertext = t.contains("\"ciphertext\"")
    val hasSenderDevice = t.contains("\"senderDeviceId\"")
    val hasEntries = t.contains("\"entries\"")
    val hasCiphertextType = t.contains("\"ciphertextType\"")
    val hasDistribution = t.contains("\"distributionMessage\"")
    return hasDistribution ||
        (hasCiphertext && (hasSenderDevice || hasEntries || hasCiphertextType))
}

// Same 35 legacy strings as DecryptPlaceholderPolicy (data, restated).
internal val LEGACY_PLACEHOLDERS183: Set<String> = setOf(
    "无法解密", "解密失败", "群密钥缺失", "密钥缺失", "decrypt failed",
    "session missing", "identity changed", "[无法解密的消息]",
    "[无法解密的群聊消息]", "[加密消息]", "[缺少会话密钥，请等待对方重新发送]",
    "[对方安全码已变化，请验证身份]", "[群成员安全码已变化，请验证身份]",
    "[缺少群聊 sender key，请等待成员重新发送]", "[群聊密钥 epoch 较新，请刷新群信息]",
    "[无法解密的图片]", "[无法解密的 gif]", "[无法解密的贴纸]",
    "[无法解密的位置]", "[无法解密的视频]", "[无法解密的语音]",
    "[无法解密的文件]", "[unable to decrypt message]",
    "[unable to decrypt group message]", "[encrypted message]",
    "[missing session key. waiting for resend]",
    "[safety number changed. verify identity]",
    "[member safety number changed. verify identity]",
    "[missing group sender key. waiting for redistribution]",
    "[newer group key epoch. refresh group info]",
    "[unable to decrypt image]", "[unable to decrypt gif]",
    "[unable to decrypt sticker]", "[unable to decrypt location]",
    "[unable to decrypt video]", "[unable to decrypt voice message]",
    "[unable to decrypt file]",
)

internal fun isPlaceholderOracle183(content: String): Boolean {
    val normalized = content.trim().lowercase()
    if (normalized.isEmpty()) return false
    return normalized in LEGACY_PLACEHOLDERS183
}

internal fun looksLikeEncryptedEnvelopeOracle183(content: String): Boolean {
    if (content.isBlank() || content.length < 16) return false
    if (isSignalWireEnvelopeOracle183(content)) return true
    return content.startsWith("eyJ") ||
        content.startsWith("{") && (content.contains("\"devices\"") || content.contains("\"ciphertext\"") || content.contains("\"type\"")) ||
        content.startsWith("MD:") || content.startsWith("SK:")
}

internal fun preferReadableContentOracle183(current: Message, candidate: Message): Message {
    val currentLooksEncrypted = looksLikeEncryptedEnvelopeOracle183(current.content)
    val candidateLooksEncrypted = looksLikeEncryptedEnvelopeOracle183(candidate.content)
    val currentLooksFailure = isPlaceholderOracle183(current.content)
    val candidateLooksFailure = isPlaceholderOracle183(candidate.content)
    return when {
        !currentLooksEncrypted && !currentLooksFailure && (candidateLooksEncrypted || candidateLooksFailure) -> current
        !candidateLooksEncrypted && !candidateLooksFailure && (currentLooksEncrypted || currentLooksFailure) -> candidate
        currentLooksFailure && candidateLooksFailure -> current
        else -> candidate
    }
}

internal fun mergeEqualRevisionOracle183(current: Message, candidate: Message): Message {
    val preferredStatus = higherDeliveryStatusOracle183(current.status, candidate.status)
    val preferredContent = preferReadableContentOracle183(current, candidate)
    return preferredContent.copy(
        status = preferredStatus,
        starred = candidate.starred,
        reactions = candidate.reactions,
        editedAt = candidate.editedAt ?: current.editedAt,
    )
}

internal fun chooseMessageVersionOracle183(current: Message, candidate: Message): Message {
    if (current.type == MessageType.REVOKED && candidate.type != MessageType.REVOKED) return current
    if (candidate.type == MessageType.REVOKED) return candidate
    val currentEdit = current.editedAt ?: Long.MIN_VALUE
    val candidateEdit = candidate.editedAt ?: Long.MIN_VALUE
    return when {
        candidateEdit > currentEdit -> {
            val preferred = preferReadableContentOracle183(current, candidate)
            preferred.copy(
                editedAt = candidate.editedAt,
                starred = candidate.starred,
                reactions = candidate.reactions,
                status = higherDeliveryStatusOracle183(current.status, candidate.status),
            )
        }
        candidateEdit < currentEdit -> {
            val preferred = preferReadableContentOracle183(candidate, current)
            preferred.copy(
                editedAt = current.editedAt,
                starred = current.starred,
                reactions = current.reactions,
                status = higherDeliveryStatusOracle183(current.status, candidate.status),
            )
        }
        else -> mergeEqualRevisionOracle183(current, candidate)
    }
}

internal fun escJsonMerge183(s: String): String {
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

internal fun messageWireJson183(m: Message): String {
    val reactions = m.reactions.joinToString(",") { r ->
        "{\"userId\":${escJsonMerge183(r.userId)},\"emoji\":${escJsonMerge183(r.emoji)},\"reactedAt\":${r.reactedAt}}"
    }
    val edited = m.editedAt?.toString() ?: "null"
    val expires = m.expiresAt?.toString() ?: "null"
    return "{\"id\":${escJsonMerge183(m.id)},\"chatId\":${escJsonMerge183(m.chatId)}," +
        "\"senderId\":${escJsonMerge183(m.senderId)},\"content\":${escJsonMerge183(m.content)}," +
        "\"type\":${escJsonMerge183(m.type.name)},\"timestamp\":${m.timestamp}," +
        "\"status\":${escJsonMerge183(m.status.name)},\"editedAt\":$edited," +
        "\"starred\":${m.starred},\"reactions\":[$reactions],\"expiresAt\":$expires," +
        "\"sealedSender\":${m.sealedSender}}"
}

// ---------- production ----------

internal fun chooseProduction183(current: Message, candidate: Message): Message =
    chooseMessageVersion(current, candidate)

internal fun mergeEqualProduction183(current: Message, candidate: Message): Message =
    mergeEqualRevision(current, candidate)

internal fun preferProduction183(current: Message, candidate: Message): Message =
    preferReadableContent(current, candidate)

// ---------- generators ----------

internal val MERGE_TYPES183: List<MessageType> = MessageType.entries

internal val MERGE_STATUSES183: List<MessageStatus> = MessageStatus.entries

internal val MERGE_CONTENTS183: List<String> = listOf(
    "hello", "你好世界", "", " ", "a", "eyJ", "MD:x", "SK:", "short",
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9eyJzdWIiOiIxMjM0NTY3ODkwIn0",
    "{\"senderDeviceId\":1,\"payloadType\":\"TEXT\",\"entries\":[{\"ciphertext\":\"abc\"}]}",
    "{\"algorithm\":\"signal\",\"ciphertext\":\"xyz\"}",
    "{\"signal-x\":1,\"distributionMessage\":\"abc\"}",
    "[{\"senderDeviceId\":2,\"ciphertext\":\"abc\"}]",
    "{\"devices\":[1,2],\"type\":\"TEXT\"}",
    "{\"type\":\"TEXT\"}",
    "{\"ciphertext\":\"abc\"}",
    "MD:abcdef0123456789",
    "SK:abcdef0123456789",
    "signal-{\"ciphertext\":\"abc\"}",
    "signal-nojson",
    "无法解密", "解密失败", "decrypt failed", "Decrypt Failed", "DECRYPT FAILED",
    "[加密消息]", "[无法解密的消息]", "  decrypt failed  ", "解密失败 ",
    "[unable to decrypt file]", "session missing",
    "prefix eyJhbGciOiJIUzI1NiJ9suffix",
    "  {\"ciphertext\":\"abc\"}  ",
    "\uD83D\uDE00emoji content here",
    "x".repeat(100),
)

internal val MERGE_EDITED183: List<Long?> = listOf(
    null, Long.MIN_VALUE, -1L, 0L, 1L, 1000L, Long.MAX_VALUE,
)

internal fun randomReactions183(rnd: Random): List<MessageReaction> {
    val n = rnd.nextInt(0, 4)
    val out = ArrayList<MessageReaction>()
    repeat(n) {
        out.add(
            MessageReaction(
                userId = listOf("alice", "bob", "u1", "u2").random(rnd),
                emoji = listOf("❤️", "👍", "😀", "+1").random(rnd),
                reactedAt = rnd.nextLong(-1000L, 100000L),
            )
        )
    }
    return out
}

internal fun randomMessage183(rnd: Random): Message = Message(
    id = listOf("m1", "m2", "m3", "id-x").random(rnd),
    chatId = listOf("c1", "c2").random(rnd),
    senderId = listOf("alice", "bob", "carol").random(rnd),
    content = MERGE_CONTENTS183.random(rnd),
    type = MERGE_TYPES183.random(rnd),
    timestamp = rnd.nextLong(-1000L, 100000L),
    status = MERGE_STATUSES183.random(rnd),
    editedAt = MERGE_EDITED183.random(rnd),
    starred = rnd.nextBoolean(),
    reactions = randomReactions183(rnd),
    expiresAt = if (rnd.nextBoolean()) null else rnd.nextLong(-1000L, 100000L),
    sealedSender = rnd.nextBoolean(),
)

internal data class MergePair183(val current: Message, val candidate: Message)

internal fun randomMergePair183(rnd: Random): MergePair183 {
    val cur = randomMessage183(rnd)
    // Same id most of the time (real merge input); sometimes different.
    val cand = if (rnd.nextInt(10) < 8) {
        randomMessage183(rnd).copy(id = cur.id)
    } else {
        randomMessage183(rnd)
    }
    return MergePair183(cur, cand)
}

internal fun mergePairWireJson183(p: MergePair183): String =
    "{\"cur\":${messageWireJson183(p.current)},\"cand\":${messageWireJson183(p.candidate)}}"

internal fun msg183(
    content: String,
    type: MessageType = MessageType.TEXT,
    status: MessageStatus = MessageStatus.SENT,
    editedAt: Long? = null,
    starred: Boolean = false,
    reactions: List<MessageReaction> = emptyList(),
): Message = Message(
    id = "m1", chatId = "c1", senderId = "alice", content = content,
    type = type, timestamp = 1000L, status = status, editedAt = editedAt,
    starred = starred, reactions = reactions,
)

class MessageVersionHelpersFuzzTest {

    private fun checkChoose183(p: MergePair183) {
        assertEquals(
            chooseMessageVersionOracle183(p.current, p.candidate),
            chooseProduction183(p.current, p.candidate),
            "choose($p)",
        )
    }

    private fun checkEqual183(p: MergePair183) {
        assertEquals(
            mergeEqualRevisionOracle183(p.current, p.candidate),
            mergeEqualProduction183(p.current, p.candidate),
            "equal($p)",
        )
    }

    private fun checkPrefer183(p: MergePair183) {
        assertEquals(
            preferReadableContentOracle183(p.current, p.candidate),
            preferProduction183(p.current, p.candidate),
            "prefer($p)",
        )
    }

    @Test
    fun pinnedMergeHelpers() {
        // REVOKED rules.
        checkChoose183(MergePair183(msg183("a", type = MessageType.REVOKED), msg183("b")))
        checkChoose183(MergePair183(msg183("a"), msg183("b", type = MessageType.REVOKED)))
        checkChoose183(
            MergePair183(
                msg183("a", type = MessageType.REVOKED),
                msg183("b", type = MessageType.REVOKED, editedAt = 5L),
            )
        )
        // editedAt ordering, including null == MIN_VALUE.
        checkChoose183(MergePair183(msg183("a", editedAt = null), msg183("b", editedAt = Long.MIN_VALUE)))
        checkChoose183(MergePair183(msg183("a", editedAt = 5L), msg183("b", editedAt = 5L)))
        checkChoose183(MergePair183(msg183("a", editedAt = 4L), msg183("b", editedAt = 5L)))
        checkChoose183(MergePair183(msg183("a", editedAt = 6L), msg183("b", editedAt = 5L)))
        checkChoose183(MergePair183(msg183("a", editedAt = Long.MAX_VALUE), msg183("b", editedAt = Long.MAX_VALUE)))
        // Newer edit keeps readable plaintext, takes newer star/reactions/status.
        val r1 = listOf(MessageReaction("alice", "❤️", 10L))
        checkChoose183(
            MergePair183(
                msg183("hello", editedAt = 1L, starred = true, reactions = r1, status = MessageStatus.READ),
                msg183("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9", editedAt = 2L, status = MessageStatus.SENT),
            )
        )
        checkChoose183(
            MergePair183(
                msg183("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9", editedAt = 2L, status = MessageStatus.SENT),
                msg183("hello", editedAt = 1L, status = MessageStatus.READ),
            )
        )
        // Equal revision: candidate owns star/reactions/editedAt, status climbs.
        checkEqual183(
            MergePair183(
                msg183("hello", editedAt = 3L, starred = true, status = MessageStatus.READ),
                msg183("world", editedAt = null, starred = false, status = MessageStatus.SENT),
            )
        )
        checkEqual183(
            MergePair183(
                msg183("hello", editedAt = null, status = MessageStatus.FAILED),
                msg183("world", editedAt = 7L, status = MessageStatus.SENT),
            )
        )
        checkEqual183(
            MergePair183(
                msg183("无法解密", status = MessageStatus.SENT),
                msg183("无法解密", status = MessageStatus.DELIVERED),
            )
        )
        // preferReadableContent branches.
        checkPrefer183(MergePair183(msg183("hello"), msg183("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9")))
        checkPrefer183(MergePair183(msg183("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"), msg183("hello")))
        checkPrefer183(MergePair183(msg183("hello"), msg183("无法解密")))
        checkPrefer183(MergePair183(msg183("无法解密"), msg183("hello")))
        checkPrefer183(MergePair183(msg183("无法解密"), msg183("decrypt failed")))
        checkPrefer183(MergePair183(msg183("hello"), msg183("world")))
        checkPrefer183(
            MergePair183(
                msg183("{\"senderDeviceId\":1,\"payloadType\":\"TEXT\",\"entries\":[{\"ciphertext\":\"abc\"}]}"),
                msg183("plain"),
            )
        )
        checkPrefer183(MergePair183(msg183("short"), msg183("eyJ")))
        checkPrefer183(MergePair183(msg183(""), msg183("MD:abcdef0123456789")))
    }

    @Test
    fun randomMergeHelpersMatchProduction() {
        val rnd = Random(183002)
        repeat(1500) { checkChoose183(randomMergePair183(rnd)) }
        repeat(1500) { checkEqual183(randomMergePair183(rnd)) }
        repeat(1500) { checkPrefer183(randomMergePair183(rnd)) }
    }

    @Test
    fun sweepMergeHelperBoundaries() {
        // Every content pair from a small pool through prefer.
        val pool = listOf(
            "hello", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9", "无法解密",
            "{\"ciphertext\":\"abc\"}", "MD:abcdef0123456789", "short", "",
        )
        for (a in pool) for (b in pool) checkPrefer183(MergePair183(msg183(a), msg183(b)))
        // Every editedAt pair through choose and equal.
        val edits: List<Long?> = listOf(null, Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)
        for (a in edits) for (b in edits) {
            checkChoose183(MergePair183(msg183("hello", editedAt = a), msg183("world", editedAt = b)))
            checkEqual183(MergePair183(msg183("hello", editedAt = a), msg183("world", editedAt = b)))
        }
        // Every status pair through equal (status ladder), both directions.
        for (a in MERGE_STATUSES183) for (b in MERGE_STATUSES183) {
            checkEqual183(MergePair183(msg183("hello", status = a), msg183("world", status = b)))
        }
        // Every type pair through choose with equal editedAt.
        for (a in MERGE_TYPES183) for (b in MERGE_TYPES183) {
            checkChoose183(MergePair183(msg183("hello", type = a, editedAt = 1L), msg183("world", type = b, editedAt = 1L)))
        }
    }
}
