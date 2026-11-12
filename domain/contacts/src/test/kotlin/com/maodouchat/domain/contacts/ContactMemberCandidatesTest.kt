package com.maodouchat.domain.contacts

import com.maodouchat.core.model.User
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactMemberCandidatesTest {

    private fun user(
        id: String,
        name: String,
        nickname: String? = null,
        email: String = "",
        isOnline: Boolean = false,
    ) = User(id = id, name = name, nickname = nickname, email = email, isOnline = isOnline)

    @Test
    fun emptyQuerySortsOnlineFirstThenByName() {
        val contacts = listOf(
            user("u1", "Zoe", isOnline = false),
            user("u2", "Amy", isOnline = true),
            user("u3", "Bob", isOnline = true),
        )
        val ids = ContactMemberCandidates.filterAndSort(contacts, "").map { it.id }
        assertEquals(listOf("u2", "u3", "u1"), ids)
    }

    @Test
    fun blankQueryTreatedAsEmpty() {
        val contacts = listOf(user("u1", "Zoe"), user("u2", "Amy", isOnline = true))
        val ids = ContactMemberCandidates.filterAndSort(contacts, "   ").map { it.id }
        assertEquals(listOf("u2", "u1"), ids)
    }

    @Test
    fun queryMatchesDisplayNameIgnoringCase() {
        val contacts = listOf(user("u1", "Alice"), user("u2", "Bob"))
        val ids = ContactMemberCandidates.filterAndSort(contacts, "ALI").map { it.id }
        assertEquals(listOf("u1"), ids)
    }

    @Test
    fun queryMatchesNameWhenDisplayNameIsNickname() {
        val contacts = listOf(user("u1", "Alice Chen", nickname = "Ali"))
        val ids = ContactMemberCandidates.filterAndSort(contacts, "chen").map { it.id }
        assertEquals(listOf("u1"), ids)
    }

    @Test
    fun queryMatchesId() {
        val contacts = listOf(user("u1", "Alice"), user("u2", "Bob"))
        val ids = ContactMemberCandidates.filterAndSort(contacts, "u2").map { it.id }
        assertEquals(listOf("u2"), ids)
    }

    @Test
    fun queryMatchesEmail() {
        val contacts = listOf(
            user("u1", "Alice", email = "alice@example.com"),
            user("u2", "Bob", email = "bob@example.com"),
        )
        val ids = ContactMemberCandidates.filterAndSort(contacts, "example.com").map { it.id }
        assertEquals(listOf("u1", "u2"), ids)
    }

    @Test
    fun queryMatchesNickname() {
        val contacts = listOf(
            user("u1", "Alice", nickname = "Ally"),
            user("u2", "Bob"),
        )
        val ids = ContactMemberCandidates.filterAndSort(contacts, "ally").map { it.id }
        assertEquals(listOf("u1"), ids)
    }

    @Test
    fun nullNicknameDoesNotCrashFilter() {
        val contacts = listOf(user("u1", "Alice", nickname = null), user("u2", "Bob"))
        val ids = ContactMemberCandidates.filterAndSort(contacts, "alice").map { it.id }
        assertEquals(listOf("u1"), ids)
    }

    @Test
    fun queryIsTrimmedBeforeMatching() {
        val contacts = listOf(user("u1", "Alice"), user("u2", "Bob"))
        val ids = ContactMemberCandidates.filterAndSort(contacts, "  bob ").map { it.id }
        assertEquals(listOf("u2"), ids)
    }

    @Test
    fun noMatchReturnsEmptyList() {
        val contacts = listOf(user("u1", "Alice"), user("u2", "Bob"))
        assertEquals(emptyList<User>(), ContactMemberCandidates.filterAndSort(contacts, "zzz"))
    }

    @Test
    fun offlineNameDoesNotOutrankOnline() {
        val contacts = listOf(
            user("u1", "Aaron", isOnline = false),
            user("u2", "Zoe", isOnline = true),
        )
        val ids = ContactMemberCandidates.filterAndSort(contacts, "").map { it.id }
        assertEquals(listOf("u2", "u1"), ids)
    }

    @Test
    fun sameStatusSortedByLowercasedDisplayName() {
        val contacts = listOf(user("u1", "banana"), user("u2", "Apple"))
        val ids = ContactMemberCandidates.filterAndSort(contacts, "").map { it.id }
        assertEquals(listOf("u2", "u1"), ids)
    }
}
