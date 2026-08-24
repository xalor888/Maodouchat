package com.maodouchat.chatdetail

import com.maodouchat.ui.screen.chatdetail.ReadCountUi
import com.maodouchat.ui.screen.chatdetail.ReadReceiptPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadReceiptPolicyTest {

    @Test
    fun senderCanAlwaysView() {
        assertTrue(
            ReadReceiptPolicy.canViewReceipts(
                viewerId = "me",
                senderId = "me",
                isGroup = false,
                viewerRole = "MEMBER",
            )
        )
        assertTrue(
            ReadReceiptPolicy.canViewReceipts(
                viewerId = "me",
                senderId = "me",
                isGroup = true,
                viewerRole = "MEMBER",
            )
        )
    }

    @Test
    fun groupAdminsCanViewOthers() {
        assertTrue(
            ReadReceiptPolicy.canViewReceipts(
                viewerId = "admin",
                senderId = "peer",
                isGroup = true,
                viewerRole = "ADMIN",
            )
        )
        assertTrue(
            ReadReceiptPolicy.canViewReceipts(
                viewerId = "owner",
                senderId = "peer",
                isGroup = true,
                viewerRole = "owner",
            )
        )
    }

    @Test
    fun ordinaryMembersCannotViewOthers() {
        assertFalse(
            ReadReceiptPolicy.canViewReceipts(
                viewerId = "member",
                senderId = "peer",
                isGroup = true,
                viewerRole = "MEMBER",
            )
        )
        assertFalse(
            ReadReceiptPolicy.canViewReceipts(
                viewerId = "peer",
                senderId = "me",
                isGroup = false,
                viewerRole = null,
            )
        )
    }

    @Test
    fun groupReadCountIsSenderOrAdminOnly() {
        assertTrue(ReadReceiptPolicy.shouldShowGroupReadCount(true, isOwnMessage = true, viewerRole = "MEMBER"))
        assertTrue(ReadReceiptPolicy.shouldShowGroupReadCount(true, isOwnMessage = false, viewerRole = "ADMIN"))
        assertFalse(ReadReceiptPolicy.shouldShowGroupReadCount(true, isOwnMessage = false, viewerRole = "MEMBER"))
        assertFalse(ReadReceiptPolicy.shouldShowGroupReadCount(false, isOwnMessage = true, viewerRole = null))
    }

    @Test
    fun groupReadCountUsesMembersNotRawReceiptRows() {
        val (read, total) = ReadReceiptPolicy.computeGroupReadCount(
            viewerId = "me",
            memberIds = listOf("me", "a", "b", "c"),
            receiptUserIds = listOf("a", "me", "ghost"),
        )
        assertEquals(1, read)
        assertEquals(3, total)
    }

    @Test
    fun groupReadCountFallsBackWhenRosterMissing() {
        val (read, total) = ReadReceiptPolicy.computeGroupReadCount(
            viewerId = "me",
            memberIds = emptyList(),
            receiptUserIds = listOf("a", "me"),
        )
        assertEquals(1, read)
        assertEquals(1, total)
    }

    @Test
    fun prefetchTakesRecentOwnDeliveredMessages() {
        val ids = ReadReceiptPolicy.outgoingMessageIdsForGroupReadPrefetch(
            viewerId = "me",
            messagesNewestLast = listOf(
                ReadReceiptPolicy.PrefetchMessage("old", "me", true),
                ReadReceiptPolicy.PrefetchMessage("peer", "a", true),
                ReadReceiptPolicy.PrefetchMessage("sk", "me", false),
                ReadReceiptPolicy.PrefetchMessage("mid", "me", true),
                ReadReceiptPolicy.PrefetchMessage("new", "me", true),
            ),
            max = 2,
        )
        assertEquals(listOf("new", "mid"), ids)
    }

    @Test
    fun incompleteGroupReadIdsSkipFullButKeepUncached() {
        val messages = listOf(
            ReadReceiptPolicy.PrefetchMessage("old", "me", true),
            ReadReceiptPolicy.PrefetchMessage("peer", "a", true),
            ReadReceiptPolicy.PrefetchMessage("mid", "me", true),
            ReadReceiptPolicy.PrefetchMessage("new", "me", true),
        )
        val ids = ReadReceiptPolicy.incompleteGroupReadMessageIds(
            viewerId = "me",
            messagesNewestLast = messages,
            counts = mapOf(
                "new" to ReadCountUi(0, 1),
                "mid" to ReadCountUi(1, 1),
            ),
            max = 4,
        )
        assertEquals(listOf("new", "old"), ids)
    }
}
