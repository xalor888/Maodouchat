package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentFriendResultPolicyTest {

    @Test
    fun `friend request and decision results carry id and status`() {
        assertEquals("Friend request r1 status=pending", AgentFriendResultPolicy.friendRequest("r1", "pending"))
        assertEquals("Accepted r1 status=accepted", AgentFriendResultPolicy.accepted("r1", "accepted"))
        assertEquals("Rejected r1 status=rejected", AgentFriendResultPolicy.rejected("r1", "rejected"))
        assertEquals("Cancelled r1 status=cancelled", AgentFriendResultPolicy.cancelled("r1", "cancelled"))
    }

    @Test
    fun `remove block and unblock results carry user id`() {
        assertEquals("Removed friend u1", AgentFriendResultPolicy.removed("u1"))
        assertEquals("Blocked u1", AgentFriendResultPolicy.blocked("u1"))
        assertEquals("Unblocked u1", AgentFriendResultPolicy.unblocked("u1"))
    }
}
