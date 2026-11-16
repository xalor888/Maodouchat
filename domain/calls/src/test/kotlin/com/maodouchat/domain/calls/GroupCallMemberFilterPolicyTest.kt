package com.maodouchat.domain.calls

import com.maodouchat.core.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupCallMemberFilterPolicyTest {

    private fun user(id: String, name: String) = User(id = id, name = name)

    private val candidates = listOf(
        user("u1", "Alice"),
        user("u2", "Bob"),
        user("u3", "alice-zhang"),
    )

    @Test
    fun `blank query returns candidates unchanged`() {
        assertEquals(candidates, GroupCallMemberFilterPolicy.filter(candidates, "   "))
    }

    @Test
    fun `query is trimmed before matching`() {
        assertEquals(listOf(candidates[0], candidates[2]),
            GroupCallMemberFilterPolicy.filter(candidates, "  alice  "))
    }

    @Test
    fun `matches by display name case-insensitively`() {
        assertTrue(GroupCallMemberFilterPolicy.matches(candidates[0], "ALI"))
        assertEquals(listOf(candidates[0], candidates[2]),
            GroupCallMemberFilterPolicy.filter(candidates, "ALICE"))
    }

    @Test
    fun `matches by user id`() {
        assertEquals(listOf(candidates[1]),
            GroupCallMemberFilterPolicy.filter(candidates, "u2"))
    }

    @Test
    fun `no match returns empty list`() {
        assertEquals(emptyList<User>(),
            GroupCallMemberFilterPolicy.filter(candidates, "zzz"))
    }

    @Test
    fun `empty candidates stays empty`() {
        assertEquals(emptyList<User>(),
            GroupCallMemberFilterPolicy.filter(emptyList(), "alice"))
    }
}
