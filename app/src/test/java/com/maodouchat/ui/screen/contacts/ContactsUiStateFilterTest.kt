package com.maodouchat.ui.screen.contacts

import com.maodouchat.data.model.User
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactsUiStateFilterTest {

    private fun user(
        id: String,
        name: String,
        email: String = "$id@example.com",
        isOnline: Boolean = false,
        nickname: String? = null,
        status: String = ""
    ) = User(
        id = id,
        name = name,
        email = email,
        isOnline = isOnline,
        nickname = nickname,
        status = status
    )

    private val alice = user("u2", "Alice", isOnline = true, status = "coding")
    private val bob = user("u3", "Bob", isOnline = false, nickname = "小猫")
    private val zhang = user("u4", "Zhang", isOnline = true, nickname = "张三")

    @Test
    fun filteredContactsMatchesQueryOnNameNicknameEmailAndStatus() {
        val state = ContactsUiState(contacts = listOf(alice, bob, zhang), searchQuery = "ali")
        assertEquals(listOf("u2"), state.filteredContacts.map { it.id })

        val byNick = state.copy(searchQuery = "小猫")
        assertEquals(listOf("u3"), byNick.filteredContacts.map { it.id })

        val byEmail = state.copy(searchQuery = "u4@example")
        assertEquals(listOf("u4"), byEmail.filteredContacts.map { it.id })

        val byStatus = state.copy(searchQuery = "CODING")
        assertEquals(listOf("u2"), byStatus.filteredContacts.map { it.id })
    }

    @Test
    fun onlineOnlyFiltersAfterQueryAndGroupedUsesDisplayNameInitial() {
        val all = ContactsUiState(contacts = listOf(alice, bob, zhang), onlineOnly = true)
        assertEquals(listOf("u2", "u4"), all.filteredContacts.map { it.id })
        assertEquals(2, all.onlineCount)

        val queried = all.copy(searchQuery = "z")
        assertEquals(listOf("u4"), queried.filteredContacts.map { it.id })

        val grouped = ContactsUiState(contacts = listOf(alice, bob, zhang)).grouped
        // grouped 用 displayName（昵称优先）：Alice→A、小猫→小、张三→张
        assertEquals(listOf("A", "小", "张"), grouped.keys.toList())
        assertEquals(listOf("u2"), grouped["A"]!!.map { it.id })
        assertEquals(listOf("u3"), grouped["小"]!!.map { it.id })
        assertEquals(listOf("u4"), grouped["张"]!!.map { it.id })
    }
}
