package com.maodouchat.domain.contacts

import com.maodouchat.core.model.User
import org.junit.Assert.assertEquals
import org.junit.Test

class UserMergePolicyTest {

    private fun user(
        id: String = "u1",
        name: String = "Ada",
        nickname: String? = null,
        avatar: String? = null,
        email: String = "",
        isOnline: Boolean = false,
        status: String = "",
        lastSeen: Long = 0
    ) = User(
        id = id,
        name = name,
        nickname = nickname,
        avatar = avatar,
        email = email,
        isOnline = isOnline,
        status = status,
        lastSeen = lastSeen
    )

    @Test
    fun `incoming non-blank fields win over existing`() {
        val incoming = user(nickname = "ada", avatar = "a.png", email = "a@x.me",
            isOnline = true, status = "busy", lastSeen = 900L)
        val existing = user(nickname = "old", avatar = "old.png", email = "old@x.me",
            isOnline = false, status = "idle", lastSeen = 100L)
        assertEquals(incoming, UserMergePolicy.merge(incoming, existing))
    }

    @Test
    fun `incoming blank fields keep existing values`() {
        val incoming = user(nickname = "  ", avatar = null, email = "",
            isOnline = false, status = " ", lastSeen = 0)
        val existing = user(nickname = "old", avatar = "old.png", email = "old@x.me",
            isOnline = true, status = "idle", lastSeen = 100L)
        val expected = user(nickname = "old", avatar = "old.png", email = "old@x.me",
            isOnline = true, status = "idle", lastSeen = 100L)
        assertEquals(expected, UserMergePolicy.merge(incoming, existing))
    }

    @Test
    fun `no existing keeps incoming and defaults blanks`() {
        val incoming = user(nickname = "ada", avatar = null, email = "",
            isOnline = false, status = "", lastSeen = 0)
        assertEquals(incoming, UserMergePolicy.merge(incoming, null))
    }

    @Test
    fun `incoming online true wins over existing offline`() {
        val incoming = user(isOnline = true)
        val existing = user(isOnline = false)
        assertEquals(true, UserMergePolicy.merge(incoming, existing).isOnline)
    }

    @Test
    fun `incoming lastSeen zero keeps existing lastSeen`() {
        val incoming = user(lastSeen = 0)
        val existing = user(lastSeen = 700L)
        assertEquals(700L, UserMergePolicy.merge(incoming, existing).lastSeen)
    }

    @Test
    fun `id and name always come from incoming`() {
        val incoming = user(id = "u2", name = "Bo")
        val existing = user(id = "u1", name = "Ada")
        val result = UserMergePolicy.merge(incoming, existing)
        assertEquals("u2", result.id)
        assertEquals("Bo", result.name)
    }
}
