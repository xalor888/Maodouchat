package com.maodouchat.domain.contacts

import com.maodouchat.core.model.FriendRequestItem
import com.maodouchat.core.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendRequestFilterPolicyTest {

    private fun req(id: String, name: String, message: String, nickname: String? = null, outgoing: Boolean = false) =
        FriendRequestItem(
            id = "r-$id",
            user = User(id = id, name = name, nickname = nickname),
            message = message,
            outgoing = outgoing,
        )

    private val incoming = listOf(
        req("u1", "Alice", "hello, add me"),
        req("u2", "Bob", "we met at school"),
    )
    private val outgoing = listOf(
        req("u3", "Carol", "hi there", outgoing = true),
    )

    @Test
    fun `filter returns all on blank query`() {
        assertEquals(incoming, FriendRequestFilterPolicy.filter(incoming, "   "))
    }

    @Test
    fun `matches displayName from nickname`() {
        val item = req("u9", "Zed", "hi", nickname = "Ally")
        assertTrue(FriendRequestFilterPolicy.matches(item, "ally"))
        assertFalse(FriendRequestFilterPolicy.matches(item, "carol"))
    }

    @Test
    fun `matches name`() {
        assertTrue(FriendRequestFilterPolicy.matches(incoming[0], "alice"))
        assertFalse(FriendRequestFilterPolicy.matches(incoming[1], "alice"))
    }

    @Test
    fun `matches id`() {
        assertTrue(FriendRequestFilterPolicy.matches(incoming[1], "U2"))
        assertFalse(FriendRequestFilterPolicy.matches(incoming[0], "u2"))
    }

    @Test
    fun `matches message`() {
        assertTrue(FriendRequestFilterPolicy.matches(incoming[1], "school"))
        assertFalse(FriendRequestFilterPolicy.matches(incoming[0], "school"))
    }

    @Test
    fun `filter returns empty when nothing matches`() {
        assertTrue(FriendRequestFilterPolicy.filter(incoming, "zzz").isEmpty())
    }

    @Test
    fun `filter is case-insensitive and trims`() {
        assertEquals(listOf(incoming[0]), FriendRequestFilterPolicy.filter(incoming, " ALICE "))
    }

    @Test
    fun `incoming and outgoing share the same predicate`() {
        assertEquals(listOf(outgoing[0]), FriendRequestFilterPolicy.filter(outgoing, "carol"))
        assertEquals(listOf(incoming[1]), FriendRequestFilterPolicy.filter(incoming, "school"))
    }
}
