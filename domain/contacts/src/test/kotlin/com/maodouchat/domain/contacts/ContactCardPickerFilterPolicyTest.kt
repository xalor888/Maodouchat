package com.maodouchat.domain.contacts

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactCardPickerFilterPolicyTest {

    private fun pair(chatId: String, user: User) = Chat(id = chatId) to user

    private val contacts = listOf(
        pair("c1", User(id = "u1", name = "Alice")),
        pair("c2", User(id = "u2", name = "Bob", nickname = "Bobby")),
        pair("c3", User(id = "u3", name = "alice-zhang")),
    )

    @Test
    fun `matches by display name`() {
        assertTrue(ContactCardPickerFilterPolicy.matches(contacts[0].second, "Ali"))
        assertTrue(ContactCardPickerFilterPolicy.matches(contacts[1].second, "Bobby"))
    }

    @Test
    fun `matches by name when no nickname`() {
        assertTrue(ContactCardPickerFilterPolicy.matches(contacts[2].second, "alice-zhang"))
    }

    @Test
    fun `blank name falls back to id as display name`() {
        assertTrue(ContactCardPickerFilterPolicy.matches(User(id = "u9", name = ""), "u9"))
    }

    @Test
    fun `no match on other user id`() {
        assertFalse(ContactCardPickerFilterPolicy.matches(contacts[0].second, "u1"))
    }

    @Test
    fun `blank query returns same list`() {
        assertEquals(contacts, ContactCardPickerFilterPolicy.filter(contacts, ""))
    }

    @Test
    fun `filter trims query and matches case-insensitively`() {
        assertEquals(
            listOf(contacts[0], contacts[2]),
            ContactCardPickerFilterPolicy.filter(contacts, "  ALICE "),
        )
    }

    @Test
    fun `filter with no match returns empty`() {
        assertEquals(emptyList<Pair<Chat, User>>(), ContactCardPickerFilterPolicy.filter(contacts, "zzz"))
    }
}
