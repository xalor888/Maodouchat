package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fuzz / random-oracle tests for [mergeMessageVersions] (checklist line 931, 33rd block).
 * Test-only, zero production changes. The oracle below is an independent
 * re-derivation of the merge rules, not a copy of the fold:
 * - a REVOKED version always wins outright (the last REVOKED in sequence);
 * - otherwise starred/reactions/editedAt come from the max-edit version
 *   (ties go to the later version), status is the highest non-FAILED rank;
 * - content follows a state machine over (acc class, candidate class, edit
 *   comparison): readable beats encrypted/failure outright, and the running
 *   max edit — not the content winner's own editedAt — decides the
 *   newer/older comparison (two failures keep the accumulator when the
 *   candidate is newer-or-equal, and take the candidate when it is older).
 * A Python mini-model differentially checks this oracle against an
 * independent transcription of the production fold (5000 random cases,
 * 0 differences) and the stated invariants (2000 cases, 0 violations).
 * Note: merge is not idempotent under edit ties — on equal revision the
 * candidate's starred/reactions win, so re-merging an older version can
 * flip them back; pinned below as current behaviour, not changed.
 */
class MessageVersionMergeFuzzTest {

    private companion object {
        const val READABLE = 0
        const val ENCRYPTED = 1
        const val FAILURE = 2
    }

    // --- oracle: envelope / placeholder classification (transcribed from production) ---

    private fun signalWireEnvelope(content: String): Boolean {
        val t = content.trimStart()
        if (!t.startsWith("{") && !t.startsWith("[{")) return false
        if (t.contains("signal-")) {
            return t.contains("\"algorithm\"") || t.contains("\"distributionMessage\"") ||
                t.contains("\"ciphertext\"") || t.contains("\"senderKey\"")
        }
        val hasCiphertext = t.contains("\"ciphertext\"")
        val hasSenderDevice = t.contains("\"senderDeviceId\"")
        val hasEntries = t.contains("\"entries\"")
        val hasCiphertextType = t.contains("\"ciphertextType\"")
        val hasDistribution = t.contains("\"distributionMessage\"")
        return hasDistribution ||
            (hasCiphertext && (hasSenderDevice || hasEntries || hasCiphertextType))
    }

    private fun looksEncrypted(content: String): Boolean {
        if (content.isBlank() || content.length < 16) return false
        if (signalWireEnvelope(content)) return true
        return content.startsWith("eyJ") ||
            content.startsWith("{") && (content.contains("\"devices\"") || content.contains("\"ciphertext\"") || content.contains("\"type\"")) ||
            content.startsWith("MD:") || content.startsWith("SK:")
    }

    private fun contentClass(content: String): Int = when {
        DecryptPlaceholderPolicy.isPlaceholder(content) -> FAILURE
        looksEncrypted(content) -> ENCRYPTED
        else -> READABLE
    }

    // --- oracle: merge ---

    private fun contentNext(acc: Message, cand: Message, runEdit: Long): Message {
        val candEdit = cand.editedAt ?: Long.MIN_VALUE
        val cmp = candEdit.compareTo(runEdit)
        val a = contentClass(acc.content)
        val c = contentClass(cand.content)
        if (c == READABLE && a != READABLE) return cand
        if (a == READABLE && c != READABLE) return acc
        if (a == FAILURE && c == FAILURE) return if (cmp >= 0) acc else cand
        return if (cmp >= 0) cand else acc
    }

    private fun expectedOne(versions: List<Message>): Message {
        val revoked = versions.lastOrNull { it.type == MessageType.REVOKED }
        if (revoked != null) return revoked
        val maxEdit = versions.maxOf { it.editedAt ?: Long.MIN_VALUE }
        val editWinner = versions.last { (it.editedAt ?: Long.MIN_VALUE) == maxEdit }
        var contentWinner = versions[0]
        var runEdit = versions[0].editedAt ?: Long.MIN_VALUE
        for (v in versions.drop(1)) {
            contentWinner = contentNext(contentWinner, v, runEdit)
            runEdit = maxOf(runEdit, v.editedAt ?: Long.MIN_VALUE)
        }
        val nonFailed = versions.map { it.status }.filter { it != MessageStatus.FAILED }
        val status = if (nonFailed.isEmpty()) MessageStatus.FAILED
        else nonFailed.maxBy { it.deliveryRank() }
        return contentWinner.copy(
            editedAt = if (versions.all { it.editedAt == null }) null else maxEdit,
            starred = editWinner.starred,
            reactions = editWinner.reactions,
            status = status,
        )
    }

    private fun expectedMerge(existing: List<Message>, incoming: List<Message>): List<Message> {
        val groups = (existing + incoming).groupBy { it.id }
        return groups.values.map { expectedOne(it) }
            .sortedWith(compareBy<Message> { it.timestamp }.thenBy { it.id })
    }

    // --- random generator ---

    private val plainContents = listOf(
        "hello", "你好", "meeting at 3pm", "see you tomorrow", "ok", "👍",
        "{hello}", "{\"foo\":1}", "MD:", "SK:", "eyJ", "x".repeat(15), "x".repeat(16),
        "", "   ", "\t\n", "无法解密的消息！", "decrypt failed!", "hello {\"type\":\"x\"} world",
    )

    private val placeholders = listOf(
        "无法解密", "解密失败", "群密钥缺失", "decrypt failed", "session missing",
        "[无法解密的消息]", "[无法解密的群聊消息]", "[加密消息]",
        "[unable to decrypt message]", "[unable to decrypt group message]", "[encrypted message]",
    )

    private fun randomContent(r: Random): String {
        return when (r.nextInt(12)) {
            0 -> plainContents[r.nextInt(plainContents.size)]
            1 -> "eyJ" + buildString {
                repeat(r.nextInt(0, 41)) { append("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=".random(r)) }
            }
            2 -> (if (r.nextBoolean()) "MD:" else "SK:") + buildString {
                repeat(r.nextInt(0, 31)) { append("0123456789abcdef".random(r)) }
            }
            3 -> {
                val inner = listOf("\"devices\":[1]", "\"ciphertext\":\"abc\"", "\"type\":\"TEXT\"", "\"foo\":1")[r.nextInt(4)]
                val s = "{$inner}"
                if (r.nextInt(10) < 7) s else "  $s"
            }
            4 -> listOf(
                "{\"senderDeviceId\":1,\"payloadType\":\"TEXT\",\"entries\":[{\"ciphertext\":\"abc\"}]}",
                "{\"signal-x\",\"algorithm\":\"AES\"}",
                "[{\"ciphertext\":\"x\"}]",
                "[{\"senderDeviceId\":1,\"ciphertext\":\"x\"}]",
                "{\"distributionMessage\":\"abc\"}",
                "{\"signal-y\",\"foo\":1}",
            )[r.nextInt(6)]
            5 -> {
                val s = placeholders[r.nextInt(placeholders.size)]
                when (r.nextInt(4)) {
                    0, 1 -> s
                    2 -> "  $s \n"
                    // Case-mix only placeholders without 'i'/'I': lowercasing 'I'
                    // is locale-sensitive, and the oracle must not depend on it.
                    else -> if (s.none { it == 'i' || it == 'I' }) s.uppercase() else s
                }
            }
            6 -> {
                val n = listOf(13, 14, 15, 16, 17)[r.nextInt(5)]
                val p = listOf("eyJ", "{", "MD:", "SK:")[r.nextInt(4)]
                p + "x".repeat(maxOf(0, n - 3))
            }
            7 -> buildString { repeat(r.nextInt(16, 61)) { append("abcdefghij ".random(r)) } }
            8 -> listOf("", " ", "\t", "\n  \t", " \n ")[r.nextInt(5)]
            9 -> "{\"signal-abc\",\"other\":1}"
            10 -> "[{\"entries\":[{\"ciphertext\":\"x\"}],\"senderDeviceId\":2}]"
            else -> plainContents[r.nextInt(plainContents.size)]
        }
    }

    private val types = listOf(
        MessageType.TEXT, MessageType.TEXT, MessageType.TEXT,
        MessageType.MARKDOWN, MessageType.IMAGE, MessageType.REVOKED,
        MessageType.SYSTEM, MessageType.VOICE,
    )

    private val statuses = listOf(
        MessageStatus.SENDING, MessageStatus.SENT, MessageStatus.DELIVERED,
        MessageStatus.READ, MessageStatus.FAILED, MessageStatus.SENT,
    )

    private fun randomMessage(r: Random, ids: List<String>): Message {
        return Message(
            id = ids[r.nextInt(ids.size)],
            chatId = "c1",
            senderId = if (r.nextBoolean()) "u1" else "u2",
            content = randomContent(r),
            type = types[r.nextInt(types.size)],
            timestamp = listOf(0L, 100L, 100L, 200L, 1000L, -5L)[r.nextInt(6)],
            status = statuses[r.nextInt(statuses.size)],
            editedAt = if (r.nextBoolean()) null
            else listOf(0L, 1L, 100L, Long.MIN_VALUE, Long.MAX_VALUE)[r.nextInt(5)],
            starred = r.nextInt(10) < 3,
            reactions = if (r.nextInt(5) == 0) listOf(MessageReaction("u1", "👍", 100L)) else emptyList(),
        )
    }

    private fun randomPair(r: Random): Pair<List<Message>, List<Message>> {
        val ids = (0 until r.nextInt(1, 7)).map { "m$it" }
        val existing = (0 until r.nextInt(0, 5)).map { randomMessage(r, ids) }
        val incoming = (0 until r.nextInt(0, 5)).map { randomMessage(r, ids) }
        return existing to incoming
    }

    // --- tests ---

    @Test
    fun mergeMatchesIndependentOracle() {
        val r = Random(3301)
        repeat(300) {
            val (existing, incoming) = randomPair(r)
            assertEquals(expectedMerge(existing, incoming), mergeMessageVersions(existing, incoming))
        }
    }

    @Test
    fun mergeOutputIsSortedWithUniqueIds() {
        val r = Random(7701)
        repeat(300) {
            val (existing, incoming) = randomPair(r)
            val out = mergeMessageVersions(existing, incoming)
            val keys = out.map { it.timestamp to it.id }
            assertEquals(keys.sortedWith(compareBy({ it.first }, { it.second })), keys)
            assertEquals(out.map { it.id }.toSet().size, out.size)
        }
    }

    @Test
    fun revokedVersionAlwaysWins() {
        val r = Random(9101)
        repeat(300) {
            val (existing, incoming) = randomPair(r)
            val out = mergeMessageVersions(existing, incoming)
            for (m in out) {
                val versions = (existing + incoming).filter { it.id == m.id }
                val lastRevoked = versions.lastOrNull { it.type == MessageType.REVOKED }
                if (lastRevoked != null) assertEquals(lastRevoked, m)
            }
        }
    }

    @Test
    fun readableContentIsNeverLost() {
        val r = Random(1123)
        repeat(300) {
            val (existing, incoming) = randomPair(r)
            val out = mergeMessageVersions(existing, incoming)
            for (m in out) {
                val versions = (existing + incoming).filter { it.id == m.id }
                // 撤回是终态：REVOKED 版本连内容一起覆盖，即使别的版本可读也不保留。
                if (versions.any { it.type == MessageType.REVOKED }) continue
                if (versions.any { contentClass(it.content) == READABLE }) {
                    assertEquals(READABLE, contentClass(m.content))
                }
            }
        }
    }

    @Test
    fun statusAndEditedAtFollowNewestRevision() {
        val r = Random(4242)
        repeat(300) {
            val (existing, incoming) = randomPair(r)
            val out = mergeMessageVersions(existing, incoming)
            for (m in out) {
                val versions = (existing + incoming).filter { it.id == m.id }
                if (versions.any { it.type == MessageType.REVOKED }) continue
                val maxEdit = versions.maxOf { it.editedAt ?: Long.MIN_VALUE }
                val editWinner = versions.last { (it.editedAt ?: Long.MIN_VALUE) == maxEdit }
                assertEquals(if (versions.all { it.editedAt == null }) null else maxEdit, m.editedAt)
                assertEquals(editWinner.starred, m.starred)
                assertEquals(editWinner.reactions, m.reactions)
                val nonFailed = versions.map { it.status }.filter { it != MessageStatus.FAILED }
                val expectedStatus = if (nonFailed.isEmpty()) MessageStatus.FAILED
                else nonFailed.maxBy { it.deliveryRank() }
                assertEquals(expectedStatus, m.status)
            }
        }
    }

    @Test
    fun looksLikeEncryptedEnvelopeBoundaryPins() {
        val cases = listOf(
            "" to false, "   " to false,
            "x".repeat(15) to false, "x".repeat(16) to false,
            "eyJ" + "x".repeat(12) to false, "eyJ" + "x".repeat(13) to true,
            "MD:" + "x".repeat(12) to false, "MD:" + "x".repeat(13) to true,
            "SK:" + "x".repeat(13) to true,
            "{\"type\":\"TEXT\"}" to false, "{\"type\":\"TEXT\",\"x\":1}" to true,
            "{\"foo\":1,\"bar\":2}" to false,
            "{\"senderDeviceId\":1,\"payloadType\":\"TEXT\",\"entries\":[{\"ciphertext\":\"abc\"}]}" to true,
            "[{\"ciphertext\":\"x\"}]" to false,
            "[{\"senderDeviceId\":1,\"ciphertext\":\"x\"}]" to true,
            "{\"signal-x\",\"algorithm\":\"AES\"}" to true,
            "{\"signal-y\",\"foo\":1}" to false,
            "  {\"ciphertext\":\"abc\",\"senderDeviceId\":1}" to true,
        )
        for ((content, expected) in cases) {
            assertEquals(expected, looksLikeEncryptedEnvelope(content), "content=$content")
        }
    }

    @Test
    fun looksLikeDecryptFailurePlaceholderPins() {
        val cases = listOf(
            "无法解密" to true, " 无法解密 " to true,
            "DECRYPT FAILED" to true,
            "[UNABLE TO DECRYPT MESSAGE]" to true,
            "[UNABLE TO DECRYPT GROUP MESSAGE]" to true,
            "[ENCRYPTED MESSAGE]" to true,
            "[无法解密的图片]" to true,
            "decrypt failed!" to false, "无法解密！" to false,
            "hello" to false, "" to false, "   " to false,
        )
        for ((content, expected) in cases) {
            assertEquals(expected, looksLikeDecryptFailurePlaceholder(content), "content=$content")
        }
    }

    @Test
    fun editTieLetsLaterVersionClearStar() {
        // Equal revision: the candidate's starred/reactions win, so merging
        // the older version back after a newer tie flips starred back.
        val v1 = Message(
            id = "m", chatId = "c1", senderId = "u1", content = "hi",
            timestamp = 100L, status = MessageStatus.SENT, editedAt = 50L, starred = false,
        )
        val v2 = v1.copy(starred = true)
        val once = mergeMessageVersions(listOf(v1), listOf(v2))
        assertEquals(true, once.single().starred)
        val twice = mergeMessageVersions(once, listOf(v1))
        assertEquals(false, twice.single().starred)
    }
}
