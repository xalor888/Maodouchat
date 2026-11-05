package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupChainModelsTest {

    @Test
    fun `chain list item keeps fields and copy semantics`() {
        val item = ChainListItem(
            id = "c1", title = "接龙", topic = "周末",
            active = true, entryCount = 3, maxEntries = 10, myJoined = false
        )
        assertEquals("c1", item.id)
        assertEquals(3, item.entryCount)
        assertEquals(true, item.copy(myJoined = true).myJoined)
    }

    @Test
    fun `chain entry item keeps fields`() {
        val entry = ChainEntryItem(id = "e1", userId = "u1", sequence = 2, content = "报名")
        assertEquals(2, entry.sequence)
        assertEquals("报名", entry.content)
    }
}
