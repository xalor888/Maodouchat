package com.maodouchat.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoutStorePolicyTest {

    @Test
    fun sameAccountLogoutKeepsEncryptedStore() {
        assertFalse(LogoutStorePolicy.destroyEncryptedDatabase(LogoutStorePolicy.Reason.LOGOUT))
        assertFalse(LogoutStorePolicy.destroyEncryptedDatabase(LogoutStorePolicy.Reason.TOKEN_EXPIRED))
    }

    @Test
    fun accountSwitchAndDeleteDestroyEncryptedStore() {
        assertTrue(LogoutStorePolicy.destroyEncryptedDatabase(LogoutStorePolicy.Reason.ACCOUNT_SWITCH))
        assertTrue(LogoutStorePolicy.destroyEncryptedDatabase(LogoutStorePolicy.Reason.DELETE_ACCOUNT))
        assertTrue(LogoutStorePolicy.destroyEncryptedDatabase(LogoutStorePolicy.Reason.TRUST_DOMAIN_CHANGE))
    }

    @Test
    fun lastOwnerLetsSameAccountReloginKeepStore() {
        assertEqualsKeep("user-a", "user-a")
        assertEqualsPurge("user-a", "user-b")
    }

    @Test
    fun keepStoreLogoutKeepsMediaCoilTransfersAndSenderKeyWork() {
        val keep = listOf(LogoutStorePolicy.Reason.LOGOUT, LogoutStorePolicy.Reason.TOKEN_EXPIRED)
        keep.forEach { reason ->
            assertFalse(reason.name, LogoutStorePolicy.destroyEncryptedDatabase(reason))
            assertFalse(reason.name, LogoutStorePolicy.wipeOrdinaryMediaCache(reason))
            assertFalse(reason.name, LogoutStorePolicy.wipeCoilDiskCache(reason))
            assertFalse(reason.name, LogoutStorePolicy.wipeInFlightAttachmentTransfers(reason))
            assertFalse(reason.name, LogoutStorePolicy.cancelSenderKeyRetryWork(reason))
        }
    }

    @Test
    fun destroyStoreLogoutWipesMediaCoilTransfersAndSenderKeyWork() {
        val destroy = listOf(
            LogoutStorePolicy.Reason.ACCOUNT_SWITCH,
            LogoutStorePolicy.Reason.DELETE_ACCOUNT,
            LogoutStorePolicy.Reason.TRUST_DOMAIN_CHANGE
        )
        destroy.forEach { reason ->
            assertTrue(reason.name, LogoutStorePolicy.destroyEncryptedDatabase(reason))
            assertTrue(reason.name, LogoutStorePolicy.wipeOrdinaryMediaCache(reason))
            assertTrue(reason.name, LogoutStorePolicy.wipeCoilDiskCache(reason))
            assertTrue(reason.name, LogoutStorePolicy.wipeInFlightAttachmentTransfers(reason))
            assertTrue(reason.name, LogoutStorePolicy.cancelSenderKeyRetryWork(reason))
        }
    }

    @Test
    fun mediaCacheFollowsEncryptedStoreRuleForEveryReason() {
        LogoutStorePolicy.Reason.entries.forEach { reason ->
            val destroy = LogoutStorePolicy.destroyEncryptedDatabase(reason)
            assertEquals(reason.name, destroy, LogoutStorePolicy.wipeOrdinaryMediaCache(reason))
            assertEquals(reason.name, destroy, LogoutStorePolicy.wipeCoilDiskCache(reason))
            assertEquals(reason.name, destroy, LogoutStorePolicy.wipeInFlightAttachmentTransfers(reason))
            assertEquals(reason.name, destroy, LogoutStorePolicy.cancelSenderKeyRetryWork(reason))
        }
    }

    private fun assertEqualsKeep(previous: String, next: String) {
        org.junit.Assert.assertEquals(
            AccountSwitchAction.KEEP_LOCAL_DATA,
            AccountIsolationPolicy.onLoginAccount(previous, next)
        )
    }

    private fun assertEqualsPurge(previous: String, next: String) {
        org.junit.Assert.assertEquals(
            AccountSwitchAction.PURGE_LOCAL_DATA,
            AccountIsolationPolicy.onLoginAccount(previous, next)
        )
    }
}
