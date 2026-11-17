package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentPostResultPolicyTest {

    @Test
    fun `create like comment and delete results carry ids`() {
        assertEquals("Created post p1", AgentPostResultPolicy.created("p1"))
        assertEquals("Post p1 likedByMe=true likes=5", AgentPostResultPolicy.liked("p1", true, 5))
        assertEquals("Post p1 likedByMe=false likes=4", AgentPostResultPolicy.liked("p1", false, 4))
        assertEquals("Commented cm1 on p1", AgentPostResultPolicy.commented("cm1", "p1"))
        assertEquals("Deleted post p1", AgentPostResultPolicy.deleted("p1"))
    }
}
