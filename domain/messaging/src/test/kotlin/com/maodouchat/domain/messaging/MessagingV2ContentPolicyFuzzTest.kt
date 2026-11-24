// Fuzz / random-oracle tests for MessagingV2ContentPolicy.accepts, through
// the public kinds that drive its two private branches:
// "KEY_REQUEST" drives acceptsSenderKeyRequest, "SERVICE" drives
// acceptsService. (checklist line 931, 137th block.) Test-only, zero
// production changes. The oracles are hand-written from the production
// source: a key request needs version 1, the exact SENDER_KEY_REQUEST
// type, no event, an empty body, no attachments, and both attributes
// present, non-blank, and within their length caps (50 / 100 UTF-16
// units); a service payload with no event is any non-reserved type,
// and with an event only EDIT / DELETE / REACTION_SET in their exact
// shapes, with a non-blank target and a reaction emoji of at most 32
// units. Blank means the probed Kotlin blank set (fuzz-78), re-verified
// here by the full-alphabet audit in drive137.
// No MessagingV2ContentPolicy fuzz test existed before this block
// (content-grep dedup 0 hits on all remote refactor branches, main's
// FuzzTest files, and all open PR titles/bodies -- the bare-name
// `acceptsService` hit in fuzz-78 is the private oracle
// acceptsServiceEnvelopeOracle for MessagingV2ServiceEnvelopePolicy,
// a different function).
package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageReaction
import com.maodouchat.core.model.MessagingV2Event
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- shared random-text helpers ----------

private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

private val TEXT_RANGES = arrayOf(
    0x20 to 0x7E,
    0x00A0 to 0x00FF, 0x0100 to 0x017F, 0x0370 to 0x03FF, 0x0400 to 0x04FF,
    0x2000 to 0x206F, 0x3000 to 0x30FF, 0x4E00 to 0x4FFF, 0xFF00 to 0xFFEF,
    0x10000 to 0x1FFFF, 0x20000 to 0x2A6DF
)

private fun randomCodePoint(rnd: Random): Int {
    val (lo, hi) = TEXT_RANGES[rnd.nextInt(TEXT_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomUnit(rnd: Random, sb: StringBuilder) {
    when (rnd.nextInt(20)) {
        0 -> sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)])
        1 -> sb.append(rnd.nextInt(0x00, 0x20).toChar())
        2 -> {
            val hi = rnd.nextBoolean()
            sb.append(if (hi) rnd.nextInt(0xD800, 0xDC00).toChar() else rnd.nextInt(0xDC00, 0xE000).toChar())
        }
        else -> sb.append(Character.toChars(randomCodePoint(rnd)))
    }
}

private fun randomText(rnd: Random, maxLen: Int): String {
    val len = rnd.nextInt(0, maxLen + 1)
    val sb = StringBuilder()
    repeat(len) { randomUnit(rnd, sb) }
    return sb.toString()
}

// ---------- oracle ----------

// The probed Kotlin blank set (fuzz-78): U+00A0/U+2007/U+202F count as
// blank, U+0085 NEL / U+200B / U+FEFF do not.
private val BLANK_UNITS = setOf(
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20,
    0xA0, 0x1680,
    0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
    0x2008, 0x2009, 0x200A,
    0x2028, 0x2029, 0x202F, 0x205F, 0x3000
)

private fun isBlankOracle(s: String): Boolean {
    for (c in s) if (c.code !in BLANK_UNITS) return false
    return true
}

private fun attrOkOracle(value: String?, maxLen: Int): Boolean =
    value != null && !isBlankOracle(value) && value.length <= maxLen

private fun acceptsSenderKeyRequestOracle(content: MessagingV2Content): Boolean =
    content.type == "SENDER_KEY_REQUEST" &&
        content.event == null &&
        content.body.isEmpty() &&
        content.attachmentIds.isEmpty() &&
        attrOkOracle(content.attributes["requestedSenderUserId"], 50) &&
        attrOkOracle(content.attributes["failedMessageId"], 100)

private fun acceptsServiceContentOracle(content: MessagingV2Content): Boolean {
    val event = content.event
    if (event == null) {
        return content.type != "EVENT" &&
            content.type != "SENDER_KEY_REQUEST" &&
            content.type != "SK_DIST"
    }
    if (content.type != "EVENT" || isBlankOracle(event.targetMessageId)) return false
    return when (event.action) {
        "EDIT" ->
            event.content != null && event.editedAt != null &&
                event.reactionEmoji == null && event.reactions.isEmpty()
        "DELETE" ->
            event.content == null && event.editedAt == null &&
                event.reactionEmoji == null && event.reactions.isEmpty()
        "REACTION_SET" -> {
            val emoji = event.reactionEmoji
            event.content == null && event.editedAt == null &&
                emoji != null && !isBlankOracle(emoji) && emoji.length <= 32 &&
                event.reactions.isEmpty()
        }
        else -> false
    }
}

private fun acceptsKeyRequestOracle(content: MessagingV2Content): Boolean =
    content.version == 1 && acceptsSenderKeyRequestOracle(content)

private fun acceptsServiceKindOracle(content: MessagingV2Content): Boolean =
    content.version == 1 && acceptsServiceContentOracle(content)

// ---------- generators (mirrored by Oracle137 GEN, seed 137002) ----------

private val MVC_VERSION_POOL = intArrayOf(-1, 0, 1, 2, 3, 100)

private val MVC_TYPE_POOL = arrayOf(
    "", "TEXT", "EVENT", "SENDER_KEY_REQUEST", "SK_DIST", "text", "event",
    "sender_key_request", "sk_dist", "IMAGE", "DATA", "EVENT ", " EVENT",
    "SENDER_KEY_REQUESTX", "X", "a\uD800b", "\uD83D\uDE00"
)

private val MVC_ACTION_POOL = arrayOf(
    "", "EDIT", "REVOKE", "DELETE", "REACTION_SET", "REACTION_SNAPSHOT",
    "DELIVERY_RECEIPT", "READ_RECEIPT", "PLAY_RECEIPT", "edit", "delete",
    "EDIT ", " EDIT", "DELET", "REACTION", "FROBNICATE"
)

private val MVC_ATTR_POOL = arrayOf(
    "", " ", "alice", "   ", "\u00A0", "a b", "a\uD800b", "\uD83D\uDE00",
    "message-1", "u12345678901234567890", "x", "\u0000"
)

private val MVC_EMOJI_POOL = arrayOf(
    "", " ", "ok", "\uD83D\uDE00", "  ", "\u00A0", "a\uD800b", "a b",
    "x", "\u0000"
)

private val MVC_TARGET_POOL = arrayOf(
    "", " ", "m1", "  ", "\u00A0", "a\uD800b", "x"
)

private val MVC_BODY_POOL = arrayOf("", "x", "hello", " ", "\u0000")

private fun genMvcVersion(rnd: Random): Int =
    if (rnd.nextInt(2) == 0) MVC_VERSION_POOL[rnd.nextInt(MVC_VERSION_POOL.size)]
    else rnd.nextInt(-2, 5)

private fun genMvcType(rnd: Random): String =
    if (rnd.nextInt(2) == 0) MVC_TYPE_POOL[rnd.nextInt(MVC_TYPE_POOL.size)]
    else randomText(rnd, 20)

private fun genMvcAction(rnd: Random): String =
    if (rnd.nextInt(2) == 0) MVC_ACTION_POOL[rnd.nextInt(MVC_ACTION_POOL.size)]
    else randomText(rnd, 14)

private fun genMvcTarget(rnd: Random): String =
    if (rnd.nextInt(2) == 0) MVC_TARGET_POOL[rnd.nextInt(MVC_TARGET_POOL.size)]
    else randomText(rnd, 20)

private fun genMvcAttrValue(rnd: Random): String? = when (rnd.nextInt(4)) {
    0 -> null
    1 -> MVC_ATTR_POOL[rnd.nextInt(MVC_ATTR_POOL.size)]
    2 -> "a".repeat(rnd.nextInt(0, 120))
    else -> randomText(rnd, 60)
}

private fun genMvcEmoji(rnd: Random): String? = when (rnd.nextInt(4)) {
    0 -> null
    1 -> MVC_EMOJI_POOL[rnd.nextInt(MVC_EMOJI_POOL.size)]
    2 -> "x".repeat(rnd.nextInt(0, 40))
    else -> randomText(rnd, 20)
}

private fun genKeyContent(rnd: Random): MessagingV2Content {
    val attrs = mutableMapOf<String, String>()
    genMvcAttrValue(rnd)?.let { attrs["requestedSenderUserId"] = it }
    genMvcAttrValue(rnd)?.let { attrs["failedMessageId"] = it }
    return MessagingV2Content(
        version = genMvcVersion(rnd),
        type = genMvcType(rnd),
        body = if (rnd.nextInt(2) == 0) MVC_BODY_POOL[rnd.nextInt(MVC_BODY_POOL.size)] else randomText(rnd, 10),
        attachmentIds = List(rnd.nextInt(0, 3)) { "a$it" },
        attributes = attrs,
        event = if (rnd.nextInt(10) == 0) MessagingV2Event("EDIT", "m1") else null,
    )
}

private fun genSvcContent(rnd: Random): MessagingV2Content {
    val hasEvent = rnd.nextBoolean()
    val event = if (!hasEvent) null else MessagingV2Event(
        action = genMvcAction(rnd),
        targetMessageId = genMvcTarget(rnd),
        content = if (rnd.nextInt(2) == 0) null else randomText(rnd, 12),
        editedAt = if (rnd.nextBoolean()) rnd.nextLong() else null,
        reactionEmoji = genMvcEmoji(rnd),
        reactions = List(rnd.nextInt(0, 3)) { MessageReaction("u", "x", 0L) },
    )
    return MessagingV2Content(
        version = genMvcVersion(rnd),
        type = genMvcType(rnd),
        event = event,
    )
}

class MessagingV2ContentPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val validKey = MessagingV2Content(
            type = "SENDER_KEY_REQUEST",
            attributes = mapOf(
                "requestedSenderUserId" to "alice",
                "failedMessageId" to "message-1",
            ),
        )
        assertEquals(true, MessagingV2ContentPolicy.accepts("KEY_REQUEST", validKey))
        // Each single violation of the key-request shape rejects.
        assertEquals(false, MessagingV2ContentPolicy.accepts("KEY_REQUEST", validKey.copy(version = 2)))
        assertEquals(false, MessagingV2ContentPolicy.accepts("KEY_REQUEST", validKey.copy(version = 0)))
        assertEquals(false, MessagingV2ContentPolicy.accepts("KEY_REQUEST", validKey.copy(type = "TEXT")))
        assertEquals(false, MessagingV2ContentPolicy.accepts("KEY_REQUEST", validKey.copy(body = "x")))
        assertEquals(false, MessagingV2ContentPolicy.accepts("KEY_REQUEST", validKey.copy(attachmentIds = listOf("att-1"))))
        assertEquals(false, MessagingV2ContentPolicy.accepts("KEY_REQUEST", validKey.copy(event = MessagingV2Event("EDIT", "m1"))))
        assertEquals(false, MessagingV2ContentPolicy.accepts("KEY_REQUEST", validKey.copy(attributes = validKey.attributes - "requestedSenderUserId")))
        assertEquals(false, MessagingV2ContentPolicy.accepts("KEY_REQUEST", validKey.copy(attributes = validKey.attributes - "failedMessageId")))
        assertEquals(false, MessagingV2ContentPolicy.accepts("KEY_REQUEST", validKey.copy(attributes = validKey.attributes + ("requestedSenderUserId" to "   "))))
        assertEquals(false, MessagingV2ContentPolicy.accepts("KEY_REQUEST", validKey.copy(attributes = validKey.attributes + ("failedMessageId" to " "))))
        assertEquals(false, MessagingV2ContentPolicy.accepts("KEY_REQUEST", validKey.copy(attributes = validKey.attributes + ("requestedSenderUserId" to "a".repeat(51)))))
        assertEquals(false, MessagingV2ContentPolicy.accepts("KEY_REQUEST", validKey.copy(attributes = validKey.attributes + ("failedMessageId" to "m".repeat(101)))))
        // Exact length caps accept.
        assertEquals(
            true,
            MessagingV2ContentPolicy.accepts(
                "KEY_REQUEST",
                validKey.copy(
                    attributes = mapOf(
                        "requestedSenderUserId" to "a".repeat(50),
                        "failedMessageId" to "m".repeat(100),
                    ),
                ),
            ),
        )
        // A key request is not data.
        assertEquals(false, MessagingV2ContentPolicy.accepts("DATA", validKey))

        // Service with no event: any non-reserved type passes.
        assertEquals(true, MessagingV2ContentPolicy.accepts("SERVICE", MessagingV2Content(type = "TEXT", body = "hello")))
        assertEquals(false, MessagingV2ContentPolicy.accepts("SERVICE", MessagingV2Content(type = "EVENT")))
        assertEquals(false, MessagingV2ContentPolicy.accepts("SERVICE", MessagingV2Content(type = "SENDER_KEY_REQUEST")))
        assertEquals(false, MessagingV2ContentPolicy.accepts("SERVICE", MessagingV2Content(type = "SK_DIST")))
        assertEquals(false, MessagingV2ContentPolicy.accepts("SERVICE", MessagingV2Content(version = 2, type = "TEXT")))
        // Service EDIT in its exact shape.
        val edit = MessagingV2Content(
            type = "EVENT",
            event = MessagingV2Event(action = "EDIT", targetMessageId = "m1", content = "updated", editedAt = 12L),
        )
        assertEquals(true, MessagingV2ContentPolicy.accepts("SERVICE", edit))
        assertEquals(false, MessagingV2ContentPolicy.accepts("SERVICE", edit.copy(event = edit.event?.copy(content = null))))
        assertEquals(false, MessagingV2ContentPolicy.accepts("SERVICE", edit.copy(event = edit.event?.copy(editedAt = null))))
        assertEquals(false, MessagingV2ContentPolicy.accepts("SERVICE", edit.copy(event = edit.event?.copy(reactionEmoji = "x"))))
        assertEquals(false, MessagingV2ContentPolicy.accepts("SERVICE", edit.copy(event = edit.event?.copy(reactions = listOf(MessageReaction("u1", "x", 0L))))))
        assertEquals(false, MessagingV2ContentPolicy.accepts("SERVICE", edit.copy(event = edit.event?.copy(targetMessageId = ""))))
        assertEquals(false, MessagingV2ContentPolicy.accepts("SERVICE", edit.copy(event = edit.event?.copy(targetMessageId = "  "))))
        assertEquals(false, MessagingV2ContentPolicy.accepts("SERVICE", edit.copy(type = "TEXT")))
        // Service DELETE in its exact shape.
        val delete = MessagingV2Content(
            type = "EVENT",
            event = MessagingV2Event(action = "DELETE", targetMessageId = "m1"),
        )
        assertEquals(true, MessagingV2ContentPolicy.accepts("SERVICE", delete))
        assertEquals(false, MessagingV2ContentPolicy.accepts("SERVICE", delete.copy(event = delete.event?.copy(content = "x"))))
        assertEquals(false, MessagingV2ContentPolicy.accepts("SERVICE", delete.copy(event = delete.event?.copy(editedAt = 5L))))
        assertEquals(false, MessagingV2ContentPolicy.accepts("SERVICE", delete.copy(event = delete.event?.copy(reactionEmoji = "x"))))
        // Service REACTION_SET in its exact shape.
        val reaction = MessagingV2Content(
            type = "EVENT",
            event = MessagingV2Event(action = "REACTION_SET", targetMessageId = "m1", reactionEmoji = "ok"),
        )
        assertEquals(true, MessagingV2ContentPolicy.accepts("SERVICE", reaction))
        assertEquals(false, MessagingV2ContentPolicy.accepts("SERVICE", reaction.copy(event = reaction.event?.copy(reactionEmoji = null))))
        assertEquals(false, MessagingV2ContentPolicy.accepts("SERVICE", reaction.copy(event = reaction.event?.copy(reactionEmoji = "  "))))
        assertEquals(false, MessagingV2ContentPolicy.accepts("SERVICE", reaction.copy(event = reaction.event?.copy(reactionEmoji = "x".repeat(33)))))
        assertEquals(true, MessagingV2ContentPolicy.accepts("SERVICE", reaction.copy(event = reaction.event?.copy(reactionEmoji = "x".repeat(32)))))
        assertEquals(false, MessagingV2ContentPolicy.accepts("SERVICE", reaction.copy(event = reaction.event?.copy(content = "x"))))
        assertEquals(false, MessagingV2ContentPolicy.accepts("SERVICE", reaction.copy(event = reaction.event?.copy(editedAt = 5L))))
        // Other actions never pass as service mutations.
        for (action in listOf("REVOKE", "REACTION_SNAPSHOT", "DELIVERY_RECEIPT", "READ_RECEIPT", "FROBNICATE")) {
            val other = MessagingV2Content(
                type = "EVENT",
                event = MessagingV2Event(action = action, targetMessageId = "m1"),
            )
            assertEquals(false, MessagingV2ContentPolicy.accepts("SERVICE", other))
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle137.genMvc consumption order exactly, so seed
        // 137002 replays the same 1500 rounds (3000 GEN lines) drive137.py ran.
        val rnd = Random(137002)
        repeat(1500) {
            val key = genKeyContent(rnd)
            assertEquals(acceptsKeyRequestOracle(key), MessagingV2ContentPolicy.accepts("KEY_REQUEST", key))
            val svc = genSvcContent(rnd)
            assertEquals(acceptsServiceKindOracle(svc), MessagingV2ContentPolicy.accepts("SERVICE", svc))
        }
    }

    @Test
    fun sweepExhaustive() {
        val validAttrs = mapOf(
            "requestedSenderUserId" to "alice",
            "failedMessageId" to "message-1",
        )
        // Key request: every version x type with otherwise valid shape.
        for (version in MVC_VERSION_POOL) {
            for (type in MVC_TYPE_POOL) {
                val c = MessagingV2Content(version = version, type = type, attributes = validAttrs)
                assertEquals(acceptsKeyRequestOracle(c), MessagingV2ContentPolicy.accepts("KEY_REQUEST", c))
            }
        }
        // Key request: every sender option x every failed option.
        val senderOpts = mutableListOf<String?>(null)
        senderOpts.addAll(MVC_ATTR_POOL)
        senderOpts.addAll(listOf("a".repeat(48), "a".repeat(49), "a".repeat(50), "a".repeat(51), "a".repeat(52)))
        senderOpts.addAll(listOf("m".repeat(98), "m".repeat(99), "m".repeat(100), "m".repeat(101), "m".repeat(102)))
        val failedOpts = mutableListOf<String?>(null)
        failedOpts.addAll(MVC_ATTR_POOL)
        failedOpts.addAll(listOf("m".repeat(98), "m".repeat(99), "m".repeat(100), "m".repeat(101), "m".repeat(102)))
        failedOpts.addAll(listOf("a".repeat(48), "a".repeat(49), "a".repeat(50), "a".repeat(51), "a".repeat(52)))
        for (sender in senderOpts) {
            for (failed in failedOpts) {
                val attrs = mutableMapOf<String, String>()
                sender?.let { attrs["requestedSenderUserId"] = it }
                failed?.let { attrs["failedMessageId"] = it }
                val c = MessagingV2Content(type = "SENDER_KEY_REQUEST", attributes = attrs)
                assertEquals(acceptsKeyRequestOracle(c), MessagingV2ContentPolicy.accepts("KEY_REQUEST", c))
            }
        }
        // Key request: body pool x attachment presence, and an event kills it.
        for (body in MVC_BODY_POOL) {
            for (att in listOf(0, 1)) {
                val c = MessagingV2Content(
                    type = "SENDER_KEY_REQUEST",
                    body = body,
                    attachmentIds = List(att) { "a$it" },
                    attributes = validAttrs,
                )
                assertEquals(acceptsKeyRequestOracle(c), MessagingV2ContentPolicy.accepts("KEY_REQUEST", c))
            }
        }
        val withEvent = MessagingV2Content(
            type = "SENDER_KEY_REQUEST",
            attributes = validAttrs,
            event = MessagingV2Event("EDIT", "m1"),
        )
        assertEquals(acceptsKeyRequestOracle(withEvent), MessagingV2ContentPolicy.accepts("KEY_REQUEST", withEvent))
        // Key request: one-unit edits of the type literal.
        checkMvcEditedType("SENDER_KEY_REQUEST")
        // Key request: every single low unit as each attribute value.
        for (code in 0x00 until 0x80) {
            val s = code.toChar().toString()
            val c1 = MessagingV2Content(
                type = "SENDER_KEY_REQUEST",
                attributes = mapOf("requestedSenderUserId" to s, "failedMessageId" to "message-1"),
            )
            assertEquals(acceptsKeyRequestOracle(c1), MessagingV2ContentPolicy.accepts("KEY_REQUEST", c1))
            val c2 = MessagingV2Content(
                type = "SENDER_KEY_REQUEST",
                attributes = mapOf("requestedSenderUserId" to "alice", "failedMessageId" to s),
            )
            assertEquals(acceptsKeyRequestOracle(c2), MessagingV2ContentPolicy.accepts("KEY_REQUEST", c2))
        }

        // Service with no event: every version x type.
        for (version in MVC_VERSION_POOL) {
            for (type in MVC_TYPE_POOL) {
                val c = MessagingV2Content(version = version, type = type)
                assertEquals(acceptsServiceKindOracle(c), MessagingV2ContentPolicy.accepts("SERVICE", c))
            }
        }
        // Service event matrix: every action x content x edited x emoji x reactions.
        val emojiOpts = listOf<String?>(null, "ok", " ", "x".repeat(32), "x".repeat(33))
        for (action in MVC_ACTION_POOL) {
            for (content in listOf<String?>(null, "updated")) {
                for (edited in listOf(false, true)) {
                    for (emoji in emojiOpts) {
                        for (reactions in listOf(0, 1)) {
                            val c = MessagingV2Content(
                                type = "EVENT",
                                event = MessagingV2Event(
                                    action = action,
                                    targetMessageId = "m1",
                                    content = content,
                                    editedAt = if (edited) 12L else null,
                                    reactionEmoji = emoji,
                                    reactions = List(reactions) { MessageReaction("u", "x", 0L) },
                                ),
                            )
                            assertEquals(acceptsServiceKindOracle(c), MessagingV2ContentPolicy.accepts("SERVICE", c))
                        }
                    }
                }
            }
        }
        // Service event: every target, for the three mutation actions.
        for (action in listOf("EDIT", "DELETE", "REACTION_SET")) {
            for (target in MVC_TARGET_POOL) {
                val c = MessagingV2Content(
                    type = "EVENT",
                    event = when (action) {
                        "EDIT" -> MessagingV2Event(action = action, targetMessageId = target, content = "updated", editedAt = 12L)
                        "DELETE" -> MessagingV2Event(action = action, targetMessageId = target)
                        else -> MessagingV2Event(action = action, targetMessageId = target, reactionEmoji = "ok")
                    },
                )
                assertEquals(acceptsServiceKindOracle(c), MessagingV2ContentPolicy.accepts("SERVICE", c))
            }
        }
        // Service REACTION_SET: emoji lengths 0..40.
        for (len in 0..40) {
            val c = MessagingV2Content(
                type = "EVENT",
                event = MessagingV2Event(action = "REACTION_SET", targetMessageId = "m1", reactionEmoji = "x".repeat(len)),
            )
            assertEquals(acceptsServiceKindOracle(c), MessagingV2ContentPolicy.accepts("SERVICE", c))
        }
        // Service event: one-unit edits of the three mutation actions.
        for (action in listOf("EDIT", "DELETE", "REACTION_SET")) {
            checkMvcEditedAction(action)
        }
        // Service event: every single low unit as emoji / target / action.
        for (code in 0x00 until 0x80) {
            val s = code.toChar().toString()
            val c1 = MessagingV2Content(
                type = "EVENT",
                event = MessagingV2Event(action = "REACTION_SET", targetMessageId = "m1", reactionEmoji = s),
            )
            assertEquals(acceptsServiceKindOracle(c1), MessagingV2ContentPolicy.accepts("SERVICE", c1))
            val c2 = MessagingV2Content(
                type = "EVENT",
                event = MessagingV2Event(action = "EDIT", targetMessageId = s, content = "updated", editedAt = 12L),
            )
            assertEquals(acceptsServiceKindOracle(c2), MessagingV2ContentPolicy.accepts("SERVICE", c2))
            val c3 = MessagingV2Content(
                type = "EVENT",
                event = MessagingV2Event(action = s, targetMessageId = "m1"),
            )
            assertEquals(acceptsServiceKindOracle(c3), MessagingV2ContentPolicy.accepts("SERVICE", c3))
        }
    }

    private fun checkMvcEditedType(target: String) {
        // One-unit edits of the key-request type literal, against the
        // otherwise valid key-request shape.
        val validAttrs = mapOf(
            "requestedSenderUserId" to "alice",
            "failedMessageId" to "message-1",
        )
        val edits = " _Xa0\u0000"
        for (pos in target.indices) {
            for (rep in edits) {
                val s = target.substring(0, pos) + rep + target.substring(pos + 1)
                val c = MessagingV2Content(type = s, attributes = validAttrs)
                assertEquals(acceptsKeyRequestOracle(c), MessagingV2ContentPolicy.accepts("KEY_REQUEST", c))
            }
            val s = target.removeRange(pos, pos + 1)
            val c = MessagingV2Content(type = s, attributes = validAttrs)
            assertEquals(acceptsKeyRequestOracle(c), MessagingV2ContentPolicy.accepts("KEY_REQUEST", c))
            val flipped = target[pos].let { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }
            val f = target.substring(0, pos) + flipped + target.substring(pos + 1)
            val cf = MessagingV2Content(type = f, attributes = validAttrs)
            assertEquals(acceptsKeyRequestOracle(cf), MessagingV2ContentPolicy.accepts("KEY_REQUEST", cf))
        }
        for (pos in 0..target.length) {
            for (ins in listOf('_', 'X')) {
                val s = target.substring(0, pos) + ins + target.substring(pos)
                val c = MessagingV2Content(type = s, attributes = validAttrs)
                assertEquals(acceptsKeyRequestOracle(c), MessagingV2ContentPolicy.accepts("KEY_REQUEST", c))
            }
        }
    }

    private fun checkMvcEditedAction(target: String) {
        // One-unit edits of a mutation action, in its otherwise valid shape.
        val edits = " _Xa0\u0000"
        for (pos in target.indices) {
            for (rep in edits) {
                val s = target.substring(0, pos) + rep + target.substring(pos + 1)
                checkMvcActionShape(target, s)
            }
            val s = target.removeRange(pos, pos + 1)
            checkMvcActionShape(target, s)
            val flipped = target[pos].let { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }
            val f = target.substring(0, pos) + flipped + target.substring(pos + 1)
            checkMvcActionShape(target, f)
        }
        for (pos in 0..target.length) {
            for (ins in listOf('_', 'X')) {
                val s = target.substring(0, pos) + ins + target.substring(pos)
                checkMvcActionShape(target, s)
            }
        }
    }

    private fun checkMvcActionShape(original: String, edited: String) {
        val event = when (original) {
            "EDIT" -> MessagingV2Event(action = edited, targetMessageId = "m1", content = "updated", editedAt = 12L)
            "DELETE" -> MessagingV2Event(action = edited, targetMessageId = "m1")
            else -> MessagingV2Event(action = edited, targetMessageId = "m1", reactionEmoji = "ok")
        }
        val c = MessagingV2Content(type = "EVENT", event = event)
        assertEquals(acceptsServiceKindOracle(c), MessagingV2ContentPolicy.accepts("SERVICE", c))
    }
}
