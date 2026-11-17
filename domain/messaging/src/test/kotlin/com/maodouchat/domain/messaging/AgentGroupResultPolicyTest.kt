package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentGroupResultPolicyTest {

    @Test
    fun `create results carry chat id and group name`() {
        assertEquals("Direct chat c1", AgentGroupResultPolicy.directChat("c1"))
        assertEquals("Group c2 name=dev", AgentGroupResultPolicy.group("c2", "dev"))
    }

    @Test
    fun `rename announcement and member results carry ids`() {
        assertEquals("Renamed c1 to dev", AgentGroupResultPolicy.renamed("c1", "dev"))
        assertEquals("Updated announcement for c1", AgentGroupResultPolicy.announcementUpdated("c1"))
        assertEquals("Added 3 members to c1", AgentGroupResultPolicy.membersAdded(3, "c1"))
        assertEquals("Removed u2 from c1", AgentGroupResultPolicy.memberRemoved("u2", "c1"))
    }

    @Test
    fun `mute and delete results carry raw values`() {
        assertEquals("Muted u2 until 1700000000000", AgentGroupResultPolicy.muted("u2", 1700000000000L))
        assertEquals("Deleted chat c1", AgentGroupResultPolicy.chatDeleted("c1"))
    }
}
