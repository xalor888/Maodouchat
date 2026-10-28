package com.maodouchat.security

import com.maodouchat.ai.AiPrivacyPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountIsolationPolicyTest {
    @Test
    fun `first login keeps local store`() {
        assertEquals(
            AccountSwitchAction.KEEP_LOCAL_DATA,
            AccountIsolationPolicy.onLoginAccount(previousUserId = null, nextUserId = "u2")
        )
        assertEquals(
            AccountSwitchAction.KEEP_LOCAL_DATA,
            AccountIsolationPolicy.onLoginAccount(previousUserId = "", nextUserId = "u2")
        )
    }

    @Test
    fun `same account re-login keeps local store`() {
        assertEquals(
            AccountSwitchAction.KEEP_LOCAL_DATA,
            AccountIsolationPolicy.onLoginAccount("user-a", "user-a")
        )
    }

    @Test
    fun `different account forces purge`() {
        assertEquals(
            AccountSwitchAction.PURGE_LOCAL_DATA,
            AccountIsolationPolicy.onLoginAccount("user-a", "user-b")
        )
    }

    @Test
    fun `blank next account does not purge`() {
        assertEquals(
            AccountSwitchAction.KEEP_LOCAL_DATA,
            AccountIsolationPolicy.onLoginAccount("user-a", null)
        )
    }

    @Test
    fun `owned rows require matching non-blank session`() {
        assertTrue(AccountIsolationPolicy.acceptsOwnedRow("u1", "u1"))
        assertFalse(AccountIsolationPolicy.acceptsOwnedRow("u1", "u2"))
        assertFalse(AccountIsolationPolicy.acceptsOwnedRow(null, "u1"))
        assertFalse(AccountIsolationPolicy.acceptsOwnedRow("u1", null))
        assertFalse(AccountIsolationPolicy.acceptsOwnedRow("", "u1"))
        assertFalse(AccountIsolationPolicy.acceptsOwnedRow("u1", "  "))
    }

    @Test
    fun `preference keys isolate accounts and match AiPrivacyPreferences`() {
        val a = AccountIsolationPolicy.preferenceKey(AiPrivacyPreferences.KEY_CONSENT, "alice")
        val b = AccountIsolationPolicy.preferenceKey(AiPrivacyPreferences.KEY_CONSENT, "bob")
        assertNotEquals(a, b)
        assertEquals(AiPrivacyPreferences.scopedKey(AiPrivacyPreferences.KEY_CONSENT, "alice"), a)
        assertEquals("items:alice", AccountIsolationPolicy.preferenceKey("items", "alice"))
    }

    @Test
    fun `padded identical accounts keep local store`() {
        assertEquals(
            AccountSwitchAction.KEEP_LOCAL_DATA,
            AccountIsolationPolicy.onLoginAccount(" user-a ", "user-a")
        )
        assertEquals(
            AccountSwitchAction.KEEP_LOCAL_DATA,
            AccountIsolationPolicy.onLoginAccount("user-a", " user-a ")
        )
    }

    @Test
    fun `whitespace-only next account does not purge`() {
        assertEquals(
            AccountSwitchAction.KEEP_LOCAL_DATA,
            AccountIsolationPolicy.onLoginAccount("user-a", "   ")
        )
    }

    @Test
    fun `owned rows trim whitespace before matching`() {
        assertTrue(AccountIsolationPolicy.acceptsOwnedRow(" u1 ", "u1"))
        assertTrue(AccountIsolationPolicy.acceptsOwnedRow("u1", "u1 "))
        assertFalse(AccountIsolationPolicy.acceptsOwnedRow(null, null))
    }

    @Test
    fun `preference key rejects blank base or user`() {
        assertThrows(IllegalArgumentException::class.java) {
            AccountIsolationPolicy.preferenceKey("", "alice")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccountIsolationPolicy.preferenceKey("  ", "alice")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccountIsolationPolicy.preferenceKey("items", " ")
        }
    }

    @Test
    fun `draft primary key rejects blank owner or chat`() {
        assertThrows(IllegalArgumentException::class.java) {
            AccountIsolationPolicy.draftPrimaryKey("", "chat-9")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccountIsolationPolicy.draftPrimaryKey("u1", "  ")
        }
    }

    @Test
    fun `draft primary key is composite owner plus chat`() {
        assertEquals("u1" to "chat-9", AccountIsolationPolicy.draftPrimaryKey("u1", "chat-9"))
        assertNotEquals(
            AccountIsolationPolicy.draftPrimaryKey("u1", "chat-9"),
            AccountIsolationPolicy.draftPrimaryKey("u2", "chat-9")
        )
    }

    @Test
    fun `both blank accounts keep local store`() {
        assertEquals(
            AccountSwitchAction.KEEP_LOCAL_DATA,
            AccountIsolationPolicy.onLoginAccount(null, null)
        )
        assertEquals(
            AccountSwitchAction.KEEP_LOCAL_DATA,
            AccountIsolationPolicy.onLoginAccount("", "")
        )
        assertEquals(
            AccountSwitchAction.KEEP_LOCAL_DATA,
            AccountIsolationPolicy.onLoginAccount("  ", " ")
        )
        assertEquals(
            AccountSwitchAction.KEEP_LOCAL_DATA,
            AccountIsolationPolicy.onLoginAccount(null, "   ")
        )
    }

    @Test
    fun `owned rows reject every blank side combination`() {
        listOf<String?>(null, "", "  ").forEach { session ->
            listOf<String?>(null, "", "  ").forEach { owner ->
                assertFalse(AccountIsolationPolicy.acceptsOwnedRow(session, owner))
            }
        }
        assertFalse(AccountIsolationPolicy.acceptsOwnedRow(null, "u1"))
        assertFalse(AccountIsolationPolicy.acceptsOwnedRow("u1", null))
    }

    @Test
    fun `preference key keeps base and user verbatim without trimming`() {
        assertEquals(" items :alice", AccountIsolationPolicy.preferenceKey(" items ", "alice"))
        assertEquals("items: alice ", AccountIsolationPolicy.preferenceKey("items", " alice "))
        assertNotEquals(
            AccountIsolationPolicy.preferenceKey("items", "alice"),
            AccountIsolationPolicy.preferenceKey("items", "alice ")
        )
    }

    @Test
    fun `draft primary key keeps owner and chat verbatim without trimming`() {
        assertEquals(" u1 " to "chat-9", AccountIsolationPolicy.draftPrimaryKey(" u1 ", "chat-9"))
        assertNotEquals(
            AccountIsolationPolicy.draftPrimaryKey("u1", "chat-9"),
            AccountIsolationPolicy.draftPrimaryKey(" u1 ", "chat-9")
        )
    }

    @Test
    fun `padded different accounts still purge`() {
        assertEquals(
            AccountSwitchAction.PURGE_LOCAL_DATA,
            AccountIsolationPolicy.onLoginAccount(" user-a ", " user-b ")
        )
        assertEquals(
            AccountSwitchAction.KEEP_LOCAL_DATA,
            AccountIsolationPolicy.onLoginAccount(" user-a ", " user-a ")
        )
    }

}
