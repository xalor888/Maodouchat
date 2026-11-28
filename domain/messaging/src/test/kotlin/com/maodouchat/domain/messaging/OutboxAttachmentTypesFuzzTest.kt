// Fuzz / random-oracle tests for isAttachmentMessageType
// (checklist line 931, 136th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: exactly five
// MessageType entries carry attachment references (IMAGE, GIF, VIDEO,
// VOICE, FILE); the other eight do not. The oracle answers on the enum
// name string, so it never touches the production set.
// No OutboxAttachmentTypes fuzz test existed before this block
// (content-grep dedup 0 hits on all remote refactor branches, main's
// FuzzTest files, and all open PR titles/bodies -- the #2171 hits are
// the 135th block's next-round candidate list, not coverage).
package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

private fun oatOracle(typeName: String): Boolean =
    typeName == "IMAGE" || typeName == "GIF" || typeName == "VIDEO" ||
        typeName == "VOICE" || typeName == "FILE"

// ---------- generators (mirrored by Oracle136 GEN, seed 136003) ----------

private val ALL_TYPE_NAMES = arrayOf(
    "TEXT", "MARKDOWN", "IMAGE", "GIF", "STICKER", "LOCATION", "VOICE",
    "VIDEO", "FILE", "NUDGE", "SK_DIST", "SYSTEM", "REVOKED"
)

private fun genOatType(rnd: Random): MessageType =
    MessageType.entries[rnd.nextInt(MessageType.entries.size)]

class OutboxAttachmentTypesFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // The five attachment types, each exactly once.
        assertEquals(true, isAttachmentMessageType(MessageType.IMAGE))
        assertEquals(true, isAttachmentMessageType(MessageType.GIF))
        assertEquals(true, isAttachmentMessageType(MessageType.VIDEO))
        assertEquals(true, isAttachmentMessageType(MessageType.VOICE))
        assertEquals(true, isAttachmentMessageType(MessageType.FILE))
        // The other eight do not carry attachment references.
        assertEquals(false, isAttachmentMessageType(MessageType.TEXT))
        assertEquals(false, isAttachmentMessageType(MessageType.MARKDOWN))
        assertEquals(false, isAttachmentMessageType(MessageType.STICKER))
        assertEquals(false, isAttachmentMessageType(MessageType.LOCATION))
        assertEquals(false, isAttachmentMessageType(MessageType.NUDGE))
        assertEquals(false, isAttachmentMessageType(MessageType.SK_DIST))
        assertEquals(false, isAttachmentMessageType(MessageType.SYSTEM))
        assertEquals(false, isAttachmentMessageType(MessageType.REVOKED))
        // The enum has exactly these thirteen entries, no more.
        assertEquals(13, MessageType.entries.size)
        assertEquals(
            listOf(
                "TEXT", "MARKDOWN", "IMAGE", "GIF", "STICKER", "LOCATION", "VOICE",
                "VIDEO", "FILE", "NUDGE", "SK_DIST", "SYSTEM", "REVOKED"
            ),
            MessageType.entries.map { it.name }
        )
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle136.genOat consumption order exactly, so seed
        // 136003 replays the same 1500 GEN lines drive136.py ran.
        val rnd = Random(136003)
        repeat(1500) {
            val type = genOatType(rnd)
            assertEquals(oatOracle(type.name), isAttachmentMessageType(type))
        }
    }

    @Test
    fun sweepExhaustive() {
        // The input space is just the thirteen entries: exhaust it,
        // twenty passes each, and pin the attachment set exactly.
        val attachmentNames = mutableListOf<String>()
        repeat(20) {
            for (type in MessageType.entries) {
                assertEquals(oatOracle(type.name), isAttachmentMessageType(type))
                if (isAttachmentMessageType(type)) attachmentNames.add(type.name)
            }
        }
        assertEquals(
            listOf("FILE", "GIF", "IMAGE", "VIDEO", "VOICE"),
            attachmentNames.distinct().sorted()
        )
        // valueOf round-trip: every entry name parses back to itself.
        for (name in ALL_TYPE_NAMES) {
            val type = MessageType.valueOf(name)
            assertEquals(name, type.name)
            assertEquals(oatOracle(name), isAttachmentMessageType(type))
        }
    }
}
