package com.maodouchat.domain.contacts

import com.maodouchat.core.model.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedUsersFilterPolicyTest {

    private fun user(id: String, name: String) = UserDto(id = id, name = name)

    private val users = listOf(
        user("u1", "Alice"),
        user("u2", "Bob"),
        user("u3", "alice-zhang"),
    )

    @Test
    fun `matches by name`() {
        assertTrue(BlockedUsersFilterPolicy.matches(users[0], "Ali"))
        assertTrue(BlockedUsersFilterPolicy.matches(users[2], "alice"))
    }

    @Test
    fun `matches by id`() {
        assertTrue(BlockedUsersFilterPolicy.matches(users[1], "u2"))
    }

    @Test
    fun `no match on other fields`() {
        assertFalse(BlockedUsersFilterPolicy.matches(users[0], "u2"))
        assertFalse(BlockedUsersFilterPolicy.matches(users[0], "zzz"))
    }

    @Test
    fun `blank query returns same list`() {
        assertEquals(users, BlockedUsersFilterPolicy.filter(users, "   "))
    }

    @Test
    fun `filter trims query and matches case-insensitively`() {
        assertEquals(listOf(users[0], users[2]), BlockedUsersFilterPolicy.filter(users, "  ALICE "))
    }

    @Test
    fun `filter by id`() {
        assertEquals(listOf(users[1]), BlockedUsersFilterPolicy.filter(users, "u2"))
    }

    @Test
    fun `filter with no match returns empty`() {
        assertEquals(emptyList<UserDto>(), BlockedUsersFilterPolicy.filter(users, "zzz"))
    }
}
