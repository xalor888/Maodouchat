package com.maodouchat.contacts

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactsIndexPolicyTest {
    @Test
    fun `initial prefers latin uppercase then cjk then hash`() {
        assertEquals("A", ContactsIndexPolicy.initialFor("alice"))
        assertEquals("Z", ContactsIndexPolicy.initialFor("Zoe"))
        assertEquals("张", ContactsIndexPolicy.initialFor("张三"))
        assertEquals("#", ContactsIndexPolicy.initialFor("123"))
        assertEquals("#", ContactsIndexPolicy.initialFor("  "))
        assertEquals("#", ContactsIndexPolicy.initialFor(""))
    }

    @Test
    fun `letter list index skips leading fixed rows and section headers`() {
        val letters = listOf("A", "B", "张")
        val sizes = mapOf("A" to 2, "B" to 1, "张" to 3)
        assertEquals(1, ContactsIndexPolicy.letterListIndex(letters, sizes, "A"))
        assertEquals(4, ContactsIndexPolicy.letterListIndex(letters, sizes, "B"))
        assertEquals(6, ContactsIndexPolicy.letterListIndex(letters, sizes, "张"))
        assertEquals(-1, ContactsIndexPolicy.letterListIndex(letters, sizes, "Q"))
    }

    @Test
    fun `groupByInitial sorts keys`() {
        val grouped = ContactsIndexPolicy.groupByInitial(
            listOf("u1" to "bob", "u2" to "alice", "u3" to "张三", "u4" to "9bot")
        )
        assertEquals(listOf("#", "A", "B", "张"), grouped.keys.toList())
        assertEquals(listOf("u2"), grouped["A"])
        assertEquals(listOf("u4"), grouped["#"])
    }
}
