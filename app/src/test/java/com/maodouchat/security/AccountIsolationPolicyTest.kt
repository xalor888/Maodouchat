package com.maodouchat.security

import com.maodouchat.ai.AiPrivacyPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun `draft primary key is composite owner plus chat`() {
        assertEquals("u1" to "chat-9", AccountIsolationPolicy.draftPrimaryKey("u1", "chat-9"))
        assertNotEquals(
            AccountIsolationPolicy.draftPrimaryKey("u1", "chat-9"),
            AccountIsolationPolicy.draftPrimaryKey("u2", "chat-9")
        )
    }
}
