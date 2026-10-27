package com.maodouchat.chatdetail

import com.maodouchat.ui.screen.chatdetail.PrefetchMessage
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
                PrefetchMessage("old", "me", true),
                PrefetchMessage("peer", "a", true),
                PrefetchMessage("sk", "me", false),
                PrefetchMessage("mid", "me", true),
                PrefetchMessage("new", "me", true),
            ),
            max = 2,
        )
        assertEquals(listOf("new", "mid"), ids)
    }

    @Test
    fun incompleteGroupReadIdsSkipFullButKeepUncached() {
        val messages = listOf(
            PrefetchMessage("old", "me", true),
            PrefetchMessage("peer", "a", true),
            PrefetchMessage("mid", "me", true),
            PrefetchMessage("new", "me", true),
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

    @Test
    fun isPrivilegedGroupRole_trimsAndUppercases() {
        assertTrue(ReadReceiptPolicy.isPrivilegedGroupRole("ADMIN"))
        assertTrue(ReadReceiptPolicy.isPrivilegedGroupRole(" owner "))
        assertTrue(ReadReceiptPolicy.isPrivilegedGroupRole("admin"))
        assertFalse(ReadReceiptPolicy.isPrivilegedGroupRole("MEMBER"))
        assertFalse(ReadReceiptPolicy.isPrivilegedGroupRole(null))
        assertFalse(ReadReceiptPolicy.isPrivilegedGroupRole(""))
    }

    @Test
    fun canViewReceipts_rejectsBlankIds() {
        assertFalse(
            ReadReceiptPolicy.canViewReceipts(
                viewerId = "",
                senderId = "peer",
                isGroup = true,
                viewerRole = "ADMIN",
            )
        )
        assertFalse(
            ReadReceiptPolicy.canViewReceipts(
                viewerId = "admin",
                senderId = "",
                isGroup = true,
                viewerRole = "ADMIN",
            )
        )
    }

    @Test
    fun computeGroupReadCount_trimsWhitespaceAndDedupesRoster() {
        val (read, total) = ReadReceiptPolicy.computeGroupReadCount(
            viewerId = "me",
            memberIds = listOf(" me ", "a", "a", "", "b"),
            receiptUserIds = listOf(" a ", "me"),
        )
        assertEquals(1, read)
        assertEquals(2, total)
    }

    @Test
    fun outgoingMessageIdsForGroupReadPrefetch_blankViewerZeroMaxAndDedupes() {
        assertEquals(
            emptyList(),
            ReadReceiptPolicy.outgoingMessageIdsForGroupReadPrefetch(
                viewerId = "",
                messagesNewestLast = listOf(PrefetchMessage("a", "me", true)),
            ),
        )
        assertEquals(
            emptyList(),
            ReadReceiptPolicy.outgoingMessageIdsForGroupReadPrefetch(
                viewerId = "me",
                messagesNewestLast = listOf(PrefetchMessage("a", "me", true)),
                max = 0,
            ),
        )
        val ids = ReadReceiptPolicy.outgoingMessageIdsForGroupReadPrefetch(
            viewerId = "me",
            messagesNewestLast = listOf(
                PrefetchMessage("a", "me", true),
                PrefetchMessage("b", "me", true),
                PrefetchMessage("a", "me", true),
            ),
            max = 8,
        )
        assertEquals(listOf("a", "b"), ids)
    }

    @Test
    fun incompleteGroupReadMessageIds_zeroTotalCountsAsComplete() {
        val messages = listOf(
            PrefetchMessage("a", "me", true),
            PrefetchMessage("b", "me", true),
            PrefetchMessage("c", "me", true),
        )
        val ids = ReadReceiptPolicy.incompleteGroupReadMessageIds(
            viewerId = "me",
            messagesNewestLast = messages,
            counts = mapOf(
                "a" to ReadCountUi(2, 2),
                "b" to ReadCountUi(0, 0),
            ),
            max = 8,
        )
        assertEquals(listOf("c"), ids)
    }

    @Test
    fun canViewReceipts_groupAdminWhitespaceRoleAndDmAdminDenied() {
        assertTrue(
            ReadReceiptPolicy.canViewReceipts(
                viewerId = "admin",
                senderId = "peer",
                isGroup = true,
                viewerRole = " admin ",
            )
        )
        assertFalse(
            ReadReceiptPolicy.canViewReceipts(
                viewerId = "admin",
                senderId = "peer",
                isGroup = false,
                viewerRole = "ADMIN",
            )
        )
    }

    @Test
    fun shouldShowGroupReadCount_ownerRoleAndDmDenied() {
        assertTrue(ReadReceiptPolicy.shouldShowGroupReadCount(true, isOwnMessage = false, viewerRole = " owner "))
        assertFalse(ReadReceiptPolicy.shouldShowGroupReadCount(false, isOwnMessage = true, viewerRole = "OWNER"))
        assertFalse(ReadReceiptPolicy.shouldShowGroupReadCount(false, isOwnMessage = false, viewerRole = "ADMIN"))
    }

    @Test
    fun computeGroupReadCount_viewerOnlyRosterFallsBackToReceipts() {
        val (read, total) = ReadReceiptPolicy.computeGroupReadCount(
            viewerId = "me",
            memberIds = listOf("me"),
            receiptUserIds = listOf("a", "me", "a"),
        )
        assertEquals(1, read)
        assertEquals(1, total)
        val (emptyRead, emptyTotal) = ReadReceiptPolicy.computeGroupReadCount(
            viewerId = "me",
            memberIds = listOf("me"),
            receiptUserIds = listOf("me"),
        )
        assertEquals(0, emptyRead)
        assertEquals(0, emptyTotal)
    }

    @Test
    fun computeGroupReadCount_allReadAndNoneReadBounds() {
        val (allRead, allTotal) = ReadReceiptPolicy.computeGroupReadCount(
            viewerId = "me",
            memberIds = listOf("me", "a", "b"),
            receiptUserIds = listOf("a", "b"),
        )
        assertEquals(2, allRead)
        assertEquals(2, allTotal)
        val (noneRead, noneTotal) = ReadReceiptPolicy.computeGroupReadCount(
            viewerId = "me",
            memberIds = listOf("me", "a", "b"),
            receiptUserIds = listOf("ghost"),
        )
        assertEquals(0, noneRead)
        assertEquals(2, noneTotal)
    }

    @Test
    fun prefetchAndIncomplete_respectMaxAndSkipBlankIds() {
        val ids = ReadReceiptPolicy.outgoingMessageIdsForGroupReadPrefetch(
            viewerId = "me",
            messagesNewestLast = listOf(
                PrefetchMessage("", "me", true),
                PrefetchMessage("x", "me", true),
                PrefetchMessage("y", "me", false),
            ),
            max = 8,
        )
        assertEquals(listOf("x"), ids)
        val incomplete = ReadReceiptPolicy.incompleteGroupReadMessageIds(
            viewerId = "me",
            messagesNewestLast = listOf(
                PrefetchMessage("a", "me", true),
                PrefetchMessage("b", "me", true),
                PrefetchMessage("c", "me", true),
                PrefetchMessage("d", "me", true),
            ),
            counts = emptyMap(),
            max = 2,
        )
        assertEquals(listOf("d", "c"), incomplete)
        assertEquals(
            emptyList(),
            ReadReceiptPolicy.incompleteGroupReadMessageIds(
                viewerId = " ",
                messagesNewestLast = listOf(PrefetchMessage("a", "me", true)),
                counts = emptyMap(),
            ),
        )
    }

}
