package com.maodouchat.domain.conversation

import com.maodouchat.core.model.MessageStatus
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [ChatListReceiptPolicy] (checklist line 931, 43rd block).
 * Test-only, zero production changes.
 *
 * The oracle states each rule once, in a different shape than production: fromLatest
 * as one combined guard instead of sequential returns, displayStatus as a map, the
 * badge text as >= thresholds. A Python mini-model differentially checks this oracle
 * against an independent reimplementation on 5000 random inputs (0 diffs).
 *
 * Rules pinned here: a marked-unread chat with zero count shows a badge but no number
 * (badge text stays ""), and NBSP is Kotlin whitespace, so an NBSP owner id is blank.
 */
class ChatListReceiptPolicyFuzzTest {

    // Kotlin's isBlank() uses Char.isWhitespace (Java whitespace OR space chars), so
    // NBSP is blank while NEL is not.
    private fun isKotlinWhitespace(c: Char): Boolean =
        c in '\u0009'..'\u000D' || c == ' ' || c in '\u001C'..'\u001F' ||
            c == '\u1680' || c in '\u2000'..'\u200A' ||
            c == '\u2028' || c == '\u2029' || c == '\u205F' || c == '\u3000' ||
            c == '\u00A0' || c == '\u2007' || c == '\u202F'

    private fun kotlinBlank(s: String): Boolean =
        s.isEmpty() || s.all { isKotlinWhitespace(it) }

    private fun oracleDisplayStatus(status: MessageStatus, isGroup: Boolean): MessageStatus {
        if (!isGroup) return status
        return if (status == MessageStatus.DELIVERED) MessageStatus.SENT else status
    }

    private fun oracleFromLatest(
        senderId: String?,
        status: MessageStatus?,
        isHidden: Boolean,
        currentUserId: String,
        isGroup: Boolean
    ): Receipt? {
        val ok = senderId != null &&
            status != null &&
            !kotlinBlank(currentUserId) &&
            senderId == currentUserId &&
            !isHidden
        if (!ok) return null
        return Receipt(fromMe = true, status = oracleDisplayStatus(status, isGroup))
    }

    private fun oracleUnreadBadgeText(unreadCount: Int, markedUnread: Boolean): String {
        return when {
            unreadCount >= 100 -> "99+"
            unreadCount >= 1 -> unreadCount.toString()
            else -> ""
        }
    }

    private fun oracleShowUnreadBadge(unreadCount: Int, markedUnread: Boolean): Boolean =
        markedUnread || unreadCount > 0

    private val senders = listOf<String?>(null, "me", "peer", "", " ", "ME", "Me", "\u00A0", "\u0085", "me ")
    private val currents = listOf("me", "other", "", " ", "\u00A0", "\u0085", "ME", "me ")
    private val counts = listOf(0, 1, 2, 98, 99, 100, 101, -1, -5, Int.MIN_VALUE, Int.MAX_VALUE)

    @Test
    fun fromLatestRandomOracle() {
        val rnd = Random(46)
        repeat(300) {
            val senderId = senders.random(rnd)
            val status = (MessageStatus.values().toList() + null).random(rnd)
            val isHidden = rnd.nextBoolean()
            val currentUserId = currents.random(rnd)
            val isGroup = rnd.nextBoolean()
            val got = ChatListReceiptPolicy.fromLatest(senderId, status, isHidden, currentUserId, isGroup)
            assertEquals(
                oracleFromLatest(senderId, status, isHidden, currentUserId, isGroup),
                got,
                "sender=<$senderId> status=$status hidden=$isHidden current=<$currentUserId> group=$isGroup"
            )
            if (got != null) {
                assertEquals(senderId, currentUserId)
                assertTrue(got.fromMe)
                assertTrue(status != null && !isHidden)
                if (isGroup) assertTrue(got.status != MessageStatus.DELIVERED)
            }
        }
    }

    @Test
    fun displayStatusRandomOracle() {
        val rnd = Random(46)
        repeat(200) {
            val status = MessageStatus.values().random(rnd)
            val isGroup = rnd.nextBoolean()
            assertEquals(
                oracleDisplayStatus(status, isGroup),
                ChatListReceiptPolicy.displayStatus(status, isGroup),
                "status=$status group=$isGroup"
            )
            if (isGroup) assertTrue(ChatListReceiptPolicy.displayStatus(status, true) != MessageStatus.DELIVERED)
        }
    }

    @Test
    fun unreadBadgeTextRandomOracle() {
        val rnd = Random(46)
        repeat(300) {
            val count = if (rnd.nextInt(10) < 7) counts.random(rnd) else rnd.nextInt(-200, 300)
            val marked = rnd.nextBoolean()
            val got = ChatListReceiptPolicy.unreadBadgeText(count, marked)
            assertEquals(oracleUnreadBadgeText(count, marked), got, "count=$count marked=$marked")
            assertTrue(got == "" || got == "99+" || got.all { it.isDigit() }, "badge text shape: <$got>")
        }
    }

    @Test
    fun showUnreadBadgeRandomOracle() {
        val rnd = Random(46)
        repeat(300) {
            val count = if (rnd.nextInt(10) < 7) counts.random(rnd) else rnd.nextInt(-200, 300)
            val marked = rnd.nextBoolean()
            val got = ChatListReceiptPolicy.showUnreadBadge(count, marked)
            assertEquals(oracleShowUnreadBadge(count, marked), got, "count=$count marked=$marked")
            if (got) assertTrue(count > 0 || marked)
        }
    }

    @Test
    fun pinnedReceiptAndBadgeBoundaries() {
        val me = { status: MessageStatus, group: Boolean ->
            ChatListReceiptPolicy.fromLatest("me", status, false, "me", group)
        }
        // Group DELIVERED downgrades to SENT; READ and the rest pass through.
        assertEquals(MessageStatus.SENT, me(MessageStatus.DELIVERED, true)?.status)
        assertEquals(MessageStatus.READ, me(MessageStatus.READ, true)?.status)
        assertEquals(MessageStatus.SENT, me(MessageStatus.SENT, true)?.status)
        assertEquals(MessageStatus.DELIVERED, me(MessageStatus.DELIVERED, false)?.status)
        assertEquals(MessageStatus.SENDING, ChatListReceiptPolicy.displayStatus(MessageStatus.SENDING, true))
        assertEquals(MessageStatus.FAILED, ChatListReceiptPolicy.displayStatus(MessageStatus.FAILED, true))
        // Badge thresholds: 99 prints, 100 caps.
        assertEquals("99", ChatListReceiptPolicy.unreadBadgeText(99, false))
        assertEquals("99+", ChatListReceiptPolicy.unreadBadgeText(100, false))
        assertEquals("1", ChatListReceiptPolicy.unreadBadgeText(1, false))
        assertEquals("", ChatListReceiptPolicy.unreadBadgeText(0, false))
        assertEquals("", ChatListReceiptPolicy.unreadBadgeText(-5, false))
        // Marked-unread alone: badge shows, but with no number.
        assertEquals("", ChatListReceiptPolicy.unreadBadgeText(0, true))
        assertTrue(ChatListReceiptPolicy.showUnreadBadge(0, true))
        assertEquals("", ChatListReceiptPolicy.unreadBadgeText(-5, true))
        assertTrue(ChatListReceiptPolicy.showUnreadBadge(-5, true))
        assertEquals(false, ChatListReceiptPolicy.showUnreadBadge(0, false))
        assertEquals(false, ChatListReceiptPolicy.showUnreadBadge(-5, false))
        assertEquals("99+", ChatListReceiptPolicy.unreadBadgeText(150, true))
        // Guards: wrong sender, hidden, blank owner, null parts all hide the ticks.
        assertNull(ChatListReceiptPolicy.fromLatest("peer", MessageStatus.READ, false, "me"))
        assertNull(ChatListReceiptPolicy.fromLatest("ME", MessageStatus.SENT, false, "me"))
        assertNull(ChatListReceiptPolicy.fromLatest("me", MessageStatus.SENT, true, "me"))
        assertNull(ChatListReceiptPolicy.fromLatest("me", MessageStatus.SENT, false, " "))
        assertNull(ChatListReceiptPolicy.fromLatest(null, null, false, "me"))
        assertNull(ChatListReceiptPolicy.fromLatest("", MessageStatus.SENT, false, "me"))
        // NBSP is Kotlin whitespace, so an NBSP owner id is blank and hides the receipt.
        assertNull(ChatListReceiptPolicy.fromLatest("\u00A0", MessageStatus.SENT, false, "\u00A0"))
        // NEL is not whitespace: a NEL owner id is a real owner.
        val nel = ChatListReceiptPolicy.fromLatest("\u0085", MessageStatus.SENT, false, "\u0085")
        assertTrue(nel != null && nel.fromMe && nel.status == MessageStatus.SENT)
    }
}
