package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionModelsTest {

    @Test
    fun `active query carries index and filter`() {
        val query = ActiveQuery(atIndex = 3, filter = "ali")
        assertEquals(3, query.atIndex)
        assertEquals("ali", query.filter)
    }

    @Test
    fun `candidate defaults to not everyone`() {
        assertFalse(Candidate(userId = "u1", displayName = "Alice").isEveryone)
        assertTrue(Candidate(userId = "__everyone__", displayName = "All", isEveryone = true).isEveryone)
    }

    @Test
    fun `insert result carries text and cursor`() {
        val result = InsertResult(text = "hi @Alice ", cursor = 10)
        assertEquals("hi @Alice ", result.text)
        assertEquals(10, result.cursor)
    }

    @Test
    fun `copy keeps other fields`() {
        val copied = Candidate(userId = "u1", displayName = "Alice").copy(displayName = "Bob")
        assertEquals("u1", copied.userId)
        assertEquals("Bob", copied.displayName)
    }
}
