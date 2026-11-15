package com.maodouchat.domain.contacts

import com.maodouchat.core.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsFriendListPolicyTest {

    private val owner = User(id = "me", name = "Me")
    private val alice = User(id = "u1", name = "Alice")
    private val bob = User(id = "u2", name = "Bob")
    private val carol = User(id = "u3", name = "Carol")
    private val friendIds = setOf("u1", "u2", "u3")

    @Test
    fun `excludes the owner even when in friend ids`() {
        val out = ContactsFriendListPolicy.visibleFriends(listOf(owner, alice), "me", friendIds + "me")
        assertEquals(listOf(alice), out)
    }

    @Test
    fun `excludes non-friends`() {
        val stranger = User(id = "u9", name = "Zed")
        val out = ContactsFriendListPolicy.visibleFriends(listOf(alice, stranger), "me", friendIds)
        assertEquals(listOf(alice), out)
    }

    @Test
    fun `keeps friends`() {
        val out = ContactsFriendListPolicy.visibleFriends(listOf(carol, alice, bob), "me", friendIds)
        assertEquals(listOf(alice, bob, carol), out)
    }

    @Test
    fun `sorts by displayName ascending`() {
        val out = ContactsFriendListPolicy.visibleFriends(listOf(carol, alice, bob), "me", friendIds)
        assertEquals(listOf("Alice", "Bob", "Carol"), out.map { it.displayName })
    }

    @Test
    fun `empty input stays empty`() {
        assertTrue(ContactsFriendListPolicy.visibleFriends(emptyList(), "me", friendIds).isEmpty())
    }

    @Test
    fun `mixed-case sort uses lowercase`() {
        val users = listOf(User(id = "a", name = "banana"), User(id = "b", name = "Apple"), User(id = "c", name = "cherry"))
        val out = ContactsFriendListPolicy.sortByDisplayName(users)
        assertEquals(listOf("Apple", "banana", "cherry"), out.map { it.displayName })
    }
}
