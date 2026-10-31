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

    @Test
    fun canViewReceipts_senderWithNullRoleStillViewsOwn() {
        assertTrue(
            ReadReceiptPolicy.canViewReceipts(
                viewerId = "me",
                senderId = "me",
                isGroup = true,
                viewerRole = null,
            )
        )
        assertTrue(
            ReadReceiptPolicy.canViewReceipts(
                viewerId = "me",
                senderId = "me",
                isGroup = false,
                viewerRole = null,
            )
        )
    }

    @Test
    fun canViewReceipts_whitespaceOnlyIdsDenied() {
        assertFalse(
            ReadReceiptPolicy.canViewReceipts(
                viewerId = "   ",
                senderId = "peer",
                isGroup = true,
                viewerRole = "ADMIN",
            )
        )
        assertFalse(
            ReadReceiptPolicy.canViewReceipts(
                viewerId = "admin",
                senderId = "   ",
                isGroup = true,
                viewerRole = "ADMIN",
            )
        )
    }

    @Test
    fun computeGroupReadCount_emptyRosterAndEmptyReceiptsReturnsZeroPair() {
        val (read, total) = ReadReceiptPolicy.computeGroupReadCount(
            viewerId = "me",
            memberIds = emptyList(),
            receiptUserIds = emptyList(),
        )
        assertEquals(0, read)
        assertEquals(0, total)
        val (viewerOnlyRead, viewerOnlyTotal) = ReadReceiptPolicy.computeGroupReadCount(
            viewerId = "me",
            memberIds = listOf("me"),
            receiptUserIds = emptyList(),
        )
        assertEquals(0, viewerOnlyRead)
        assertEquals(0, viewerOnlyTotal)
    }

    @Test
    fun outgoingMessageIdsForGroupReadPrefetch_viewerIdTrimmedForSenderMatch() {
        val ids = ReadReceiptPolicy.outgoingMessageIdsForGroupReadPrefetch(
            viewerId = " me ",
            messagesNewestLast = listOf(PrefetchMessage("a", "me", true)),
        )
        assertEquals(listOf("a"), ids)
    }

    @Test
    fun incompleteAndPrefetch_negativeMaxReturnsEmpty() {
        assertEquals(
            emptyList(),
            ReadReceiptPolicy.outgoingMessageIdsForGroupReadPrefetch(
                viewerId = "me",
                messagesNewestLast = listOf(PrefetchMessage("a", "me", true)),
                max = -3,
            ),
        )
        assertEquals(
            emptyList(),
            ReadReceiptPolicy.incompleteGroupReadMessageIds(
                viewerId = "me",
                messagesNewestLast = listOf(PrefetchMessage("a", "me", true)),
                counts = emptyMap(),
                max = -1,
            ),
        )
    }

    @Test
    fun canViewReceipts_senderMatchIsExactNotTrimmed() {
        assertFalse(
            ReadReceiptPolicy.canViewReceipts(
                viewerId = "me",
                senderId = " me ",
                isGroup = false,
                viewerRole = null,
            )
        )
        assertFalse(
            ReadReceiptPolicy.canViewReceipts(
                viewerId = "me",
                senderId = " me ",
                isGroup = true,
                viewerRole = "MEMBER",
            )
        )
    }

    @Test
    fun computeGroupReadCount_duplicateAndBlankReceiptsIgnored() {
        val (read, total) = ReadReceiptPolicy.computeGroupReadCount(
            viewerId = "me",
            memberIds = listOf("me", "a", "b"),
            receiptUserIds = listOf("a", "a", "ghost", " "),
        )
        assertEquals(1, read)
        assertEquals(2, total)
    }

    @Test
    fun shouldShowGroupReadCount_nullRoleNonOwnerDenied() {
        assertFalse(ReadReceiptPolicy.shouldShowGroupReadCount(true, isOwnMessage = false, viewerRole = null))
    }

    @Test
    fun prefetchKeepsNewestFirstAcrossInterleavedPeers() {
        val ids = ReadReceiptPolicy.outgoingMessageIdsForGroupReadPrefetch(
            viewerId = "me",
            messagesNewestLast = listOf(
                PrefetchMessage("a", "me", true),
                PrefetchMessage("b", "peer", true),
                PrefetchMessage("c", "me", true),
                PrefetchMessage("d", "peer", true),
                PrefetchMessage("e", "me", true),
            ),
            max = 8,
        )
        assertEquals(listOf("e", "c", "a"), ids)
    }

    @Test
    fun incompleteDropsFullyReadWithinMaxWindow() {
        val messages = listOf(
            PrefetchMessage("a", "me", true),
            PrefetchMessage("b", "me", true),
            PrefetchMessage("c", "me", true),
            PrefetchMessage("d", "me", true),
            PrefetchMessage("e", "me", true),
        )
        val ids = ReadReceiptPolicy.incompleteGroupReadMessageIds(
            viewerId = "me",
            messagesNewestLast = messages,
            counts = mapOf(
                "e" to ReadCountUi(1, 1),
                "d" to ReadCountUi(1, 2),
            ),
            max = 3,
        )
        assertEquals(listOf("d", "c"), ids)
    }

    @Test
    fun computeGroupReadCount_viewerIdTrimmedBeforeRosterExclusion() {
        val (read, total) = ReadReceiptPolicy.computeGroupReadCount(
            viewerId = " me ",
            memberIds = listOf("me", "a"),
            receiptUserIds = listOf("a"),
        )
        assertEquals(1, read)
        assertEquals(1, total)
    }

    @Test
    fun computeGroupReadCount_fallbackDropsViewerReceipt() {
        val (read, total) = ReadReceiptPolicy.computeGroupReadCount(
            viewerId = "me",
            memberIds = emptyList(),
            receiptUserIds = listOf(" me ", "a"),
        )
        assertEquals(1, read)
        assertEquals(1, total)
    }

    @Test
    fun prefetchMaxOneKeepsOnlyNewest() {
        val ids = ReadReceiptPolicy.outgoingMessageIdsForGroupReadPrefetch(
            viewerId = "me",
            messagesNewestLast = listOf(
                PrefetchMessage("old", "me", true),
                PrefetchMessage("new", "me", true),
            ),
            max = 1,
        )
        assertEquals(listOf("new"), ids)
    }

    @Test
    fun incompleteSkipsIneligibleMessagesEvenWhenUncached() {
        val ids = ReadReceiptPolicy.incompleteGroupReadMessageIds(
            viewerId = "me",
            messagesNewestLast = listOf(PrefetchMessage("s", "me", false)),
            counts = emptyMap(),
            max = 4,
        )
        assertEquals(emptyList(), ids)
    }

    @Test
    fun incompleteExcludesOverReadCounts() {
        val ids = ReadReceiptPolicy.incompleteGroupReadMessageIds(
            viewerId = "me",
            messagesNewestLast = listOf(PrefetchMessage("a", "me", true)),
            counts = mapOf("a" to ReadCountUi(3, 2)),
            max = 4,
        )
        assertEquals(emptyList(), ids)
    }


    @Test
    fun prefetchFiltersEligibilityBeforeTakingMax() {
        val ids = ReadReceiptPolicy.outgoingMessageIdsForGroupReadPrefetch(
            viewerId = "me",
            messagesNewestLast = listOf(
                PrefetchMessage("old", "me", true),
                PrefetchMessage("mid", "me", false),
                PrefetchMessage("new", "peer", true),
            ),
            max = 1,
        )
        assertEquals(listOf("old"), ids)
    }

    @Test
    fun incompleteWindowCapsBeforeCompletenessFilter() {
        // max 先在预拉阶段截断最新消息，再筛未读满：窗口外的未读消息本轮不补。
        val ids = ReadReceiptPolicy.incompleteGroupReadMessageIds(
            viewerId = "me",
            messagesNewestLast = listOf(
                PrefetchMessage("a", "me", true),
                PrefetchMessage("b", "me", true),
                PrefetchMessage("c", "me", true),
                PrefetchMessage("d", "me", true),
                PrefetchMessage("e", "me", true),
            ),
            counts = mapOf(
                "e" to ReadCountUi(1, 1),
                "d" to ReadCountUi(1, 1),
                "c" to ReadCountUi(0, 1),
            ),
            max = 2,
        )
        assertEquals(emptyList(), ids)
    }

    @Test
    fun incompletePartialReadCountsAsIncomplete() {
        val ids = ReadReceiptPolicy.incompleteGroupReadMessageIds(
            viewerId = "me",
            messagesNewestLast = listOf(PrefetchMessage("a", "me", true)),
            counts = mapOf("a" to ReadCountUi(1, 3)),
            max = 4,
        )
        assertEquals(listOf("a"), ids)
    }

    @Test
    fun incompleteDedupesRepeatedMessageIds() {
        val ids = ReadReceiptPolicy.incompleteGroupReadMessageIds(
            viewerId = "me",
            messagesNewestLast = listOf(
                PrefetchMessage("a", "me", true),
                PrefetchMessage("a", "me", true),
            ),
            counts = emptyMap(),
            max = 4,
        )
        assertEquals(listOf("a"), ids)
    }

    @Test
    fun canViewReceipts_roleMatchIsExactAfterNormalization() {
        assertTrue(
            ReadReceiptPolicy.canViewReceipts(
                viewerId = "admin",
                senderId = "peer",
                isGroup = true,
                viewerRole = "AdMiN",
            )
        )
        assertTrue(
            ReadReceiptPolicy.canViewReceipts(
                viewerId = "owner",
                senderId = "peer",
                isGroup = true,
                viewerRole = "OWNER ",
            )
        )
        assertFalse(
            ReadReceiptPolicy.canViewReceipts(
                viewerId = "admin",
                senderId = "peer",
                isGroup = true,
                viewerRole = "SUPERADMIN",
            )
        )
    }

    @Test
    fun canViewReceiptsDmOwnerRoleDeniedForOthers() {
        assertFalse(
            ReadReceiptPolicy.canViewReceipts(
                viewerId = "owner",
                senderId = "peer",
                isGroup = false,
                viewerRole = "OWNER",
            )
        )
        assertFalse(
            ReadReceiptPolicy.canViewReceipts(
                viewerId = "owner",
                senderId = "peer",
                isGroup = false,
                viewerRole = " owner ",
            )
        )
    }

    @Test
    fun computeGroupReadCountFallbackCountsGhostReceipts() {
        val (read, total) = ReadReceiptPolicy.computeGroupReadCount(
            viewerId = "me",
            memberIds = emptyList(),
            receiptUserIds = listOf("ghost", "me"),
        )
        assertEquals(1, read)
        assertEquals(1, total)
        val (rosterRead, rosterTotal) = ReadReceiptPolicy.computeGroupReadCount(
            viewerId = "me",
            memberIds = listOf("me"),
            receiptUserIds = listOf("ghost"),
        )
        assertEquals(1, rosterRead)
        assertEquals(1, rosterTotal)
    }

    @Test
    fun prefetchDedupeAppliesBeforeMax() {
        val ids = ReadReceiptPolicy.outgoingMessageIdsForGroupReadPrefetch(
            viewerId = "me",
            messagesNewestLast = listOf(
                PrefetchMessage("a", "me", true),
                PrefetchMessage("b", "me", true),
                PrefetchMessage("a", "me", true),
                PrefetchMessage("c", "peer", true),
                PrefetchMessage("d", "me", true),
            ),
            max = 2,
        )
        assertEquals(listOf("d", "a"), ids)
    }

    @Test
    fun incompleteSkipsFullyReadNewestButKeepsPartialAndUncached() {
        val ids = ReadReceiptPolicy.incompleteGroupReadMessageIds(
            viewerId = "me",
            messagesNewestLast = listOf(
                PrefetchMessage("a", "me", true),
                PrefetchMessage("b", "me", true),
                PrefetchMessage("c", "me", true),
            ),
            counts = mapOf(
                "c" to ReadCountUi(1, 1),
                "b" to ReadCountUi(1, 2),
            ),
            max = 3,
        )
        assertEquals(listOf("b", "a"), ids)
    }

    @Test
    fun canViewReceipts_groupOwnerWithPaddedLowercaseRoleViewsPeer() {
        assertTrue(
            ReadReceiptPolicy.canViewReceipts(
                viewerId = "o",
                senderId = "p",
                isGroup = true,
                viewerRole = " owner ",
            )
        )
    }

    @Test
    fun canViewReceipts_adminLikeRolesCannotViewPeer() {
        assertFalse(
            ReadReceiptPolicy.canViewReceipts(
                viewerId = "a",
                senderId = "p",
                isGroup = true,
                viewerRole = "ADMINISTRATOR",
            )
        )
        assertFalse(
            ReadReceiptPolicy.canViewReceipts(
                viewerId = "a",
                senderId = "p",
                isGroup = true,
                viewerRole = "OWNER2",
            )
        )
    }

    @Test
    fun computeGroupReadCount_singleMemberAndEmptyFallbackBounds() {
        val (read, total) = ReadReceiptPolicy.computeGroupReadCount(
            viewerId = "me",
            memberIds = listOf("me", "a"),
            receiptUserIds = listOf("a"),
        )
        assertEquals(1, read)
        assertEquals(1, total)
        val (emptyRead, emptyTotal) = ReadReceiptPolicy.computeGroupReadCount(
            viewerId = "me",
            memberIds = listOf("me"),
            receiptUserIds = emptyList(),
        )
        assertEquals(0, emptyRead)
        assertEquals(0, emptyTotal)
    }

    @Test
    fun prefetchReturnsAllEligibleWhenMaxExceedsCount() {
        val ids = ReadReceiptPolicy.outgoingMessageIdsForGroupReadPrefetch(
            viewerId = "me",
            messagesNewestLast = listOf(
                PrefetchMessage("old", "me", true),
                PrefetchMessage("peer", "peer", true),
                PrefetchMessage("new", "me", true),
            ),
            max = 8,
        )
        assertEquals(listOf("new", "old"), ids)
    }

    @Test
    fun prefetchDedupesRepeatedIdsKeepingNewestFirst() {
        val ids = ReadReceiptPolicy.outgoingMessageIdsForGroupReadPrefetch(
            viewerId = "me",
            messagesNewestLast = listOf(
                PrefetchMessage("x", "me", true),
                PrefetchMessage("y", "me", true),
                PrefetchMessage("x", "me", true),
                PrefetchMessage("z", "me", true),
            ),
            max = 8,
        )
        assertEquals(listOf("z", "x", "y"), ids)
    }

    @Test
    fun isPrivilegedGroupRoleRejectsAdminLikeVariants() {
        assertFalse(ReadReceiptPolicy.isPrivilegedGroupRole("ADMINISTRATOR"))
        assertFalse(ReadReceiptPolicy.isPrivilegedGroupRole("SUPERADMIN"))
        assertFalse(ReadReceiptPolicy.isPrivilegedGroupRole("OWNER2"))
        assertTrue(ReadReceiptPolicy.isPrivilegedGroupRole("owner"))
    }

}
