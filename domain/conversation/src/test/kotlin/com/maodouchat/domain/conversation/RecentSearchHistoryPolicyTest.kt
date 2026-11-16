package com.maodouchat.domain.conversation

import kotlin.test.Test
import kotlin.test.assertEquals

class RecentSearchHistoryPolicyTest {

    @Test
    fun `empty history pushes one`() {
        assertEquals(listOf("hello"), RecentSearchHistoryPolicy.push(emptyList(), "hello"))
    }

    @Test
    fun `duplicate query case-insensitive stays single at front`() {
        assertEquals(
            listOf("hello", "world"),
            RecentSearchHistoryPolicy.push(listOf("Hello", "world"), "hello"),
        )
    }

    @Test
    fun `ninth query drops the oldest`() {
        val existing = (1..8).map { "q$it" }
        val updated = RecentSearchHistoryPolicy.push(existing, "new")
        assertEquals(8, updated.size)
        assertEquals("new", updated.first())
        assertEquals("q7", updated.last())
    }

    @Test
    fun `new query goes first keeping order`() {
        assertEquals(
            listOf("c", "a", "b"),
            RecentSearchHistoryPolicy.push(listOf("a", "b"), "c"),
        )
    }

    @Test
    fun `three existing plus new gives four`() {
        assertEquals(4, RecentSearchHistoryPolicy.push(listOf("a", "b", "c"), "d").size)
    }

    @Test
    fun `case-only difference moves entry to front`() {
        assertEquals(
            listOf("HELLO", "x"),
            RecentSearchHistoryPolicy.push(listOf("x", "hello"), "HELLO"),
        )
    }
}
