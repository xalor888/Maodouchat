package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactsIndexPolicyTest {

    @Test
    fun `groupByInitial sorts sections and keeps ids`() {
        val grouped = ContactsIndexPolicy.groupByInitial(
            listOf("u1" to "Zoe", "u2" to "alice", "u3" to "张三", "u4" to "amy")
        )
        assertEquals(listOf("A", "Z", "张"), grouped.keys.toList())
        assertEquals(listOf("u2", "u4"), grouped["A"])
    }

    @Test
    fun `letterListIndex honors leading fixed items`() {
        val letters = listOf("A", "B")
        val sizes = mapOf("A" to 2, "B" to 1)
        assertEquals(10, ContactsIndexPolicy.letterListIndex(letters, sizes, "A", leadingFixedItems = 10))
        assertEquals(13, ContactsIndexPolicy.letterListIndex(letters, sizes, "B", leadingFixedItems = 10))
    }

    @Test
    fun `leadingFixedItemCount counts only non-empty sections`() {
        assertEquals(3 + 2, ContactsIndexPolicy.leadingFixedItemCount(0, 1, 0))
        assertEquals(3 + 4, ContactsIndexPolicy.leadingFixedItemCount(0, 0, 3))
    }
}
