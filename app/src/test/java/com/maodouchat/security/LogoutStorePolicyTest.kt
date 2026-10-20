package com.maodouchat.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoutStorePolicyTest {

    @Test
    fun sameAccountLogoutKeepsEncryptedStore() {
        assertFalse(LogoutStorePolicy.destroyEncryptedDatabase(Reason.LOGOUT))
        assertFalse(LogoutStorePolicy.destroyEncryptedDatabase(Reason.TOKEN_EXPIRED))
    }

    @Test
    fun accountSwitchAndDeleteDestroyEncryptedStore() {
        assertTrue(LogoutStorePolicy.destroyEncryptedDatabase(Reason.ACCOUNT_SWITCH))
        assertTrue(LogoutStorePolicy.destroyEncryptedDatabase(Reason.DELETE_ACCOUNT))
        assertTrue(LogoutStorePolicy.destroyEncryptedDatabase(Reason.TRUST_DOMAIN_CHANGE))
    }

    @Test
    fun lastOwnerLetsSameAccountReloginKeepStore() {
        assertEqualsKeep("user-a", "user-a")
        assertEqualsPurge("user-a", "user-b")
    }

    @Test
    fun keepStoreLogoutKeepsMediaCoilTransfersAndSenderKeyWork() {
        val keep = listOf(Reason.LOGOUT, Reason.TOKEN_EXPIRED)
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
            Reason.ACCOUNT_SWITCH,
            Reason.DELETE_ACCOUNT,
            Reason.TRUST_DOMAIN_CHANGE
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
        Reason.entries.forEach { reason ->
            val destroy = LogoutStorePolicy.destroyEncryptedDatabase(reason)
            assertEquals(reason.name, destroy, LogoutStorePolicy.wipeOrdinaryMediaCache(reason))
            assertEquals(reason.name, destroy, LogoutStorePolicy.wipeCoilDiskCache(reason))
            assertEquals(reason.name, destroy, LogoutStorePolicy.wipeInFlightAttachmentTransfers(reason))
            assertEquals(reason.name, destroy, LogoutStorePolicy.cancelSenderKeyRetryWork(reason))
        }
    }

    @Test
    fun destroyVerdictTruthTableCoversEveryReason() {
        val expected = mapOf(
            Reason.LOGOUT to false,
            Reason.TOKEN_EXPIRED to false,
            Reason.ACCOUNT_SWITCH to true,
            Reason.DELETE_ACCOUNT to true,
            Reason.TRUST_DOMAIN_CHANGE to true
        )
        expected.forEach { (reason, verdict) ->
            assertEquals(reason.name, verdict, LogoutStorePolicy.destroyEncryptedDatabase(reason))
        }
    }

    @Test
    fun reasonEnumStaysExactlyFiveEntries() {
        assertEquals(5, Reason.entries.size)
        assertTrue(
            Reason.entries.containsAll(
                listOf(
                    Reason.LOGOUT,
                    Reason.TOKEN_EXPIRED,
                    Reason.ACCOUNT_SWITCH,
                    Reason.DELETE_ACCOUNT,
                    Reason.TRUST_DOMAIN_CHANGE
                )
            )
        )
    }

    @Test
    fun destroyVerdictIsStableAcrossRepeatedCalls() {
        Reason.entries.forEach { reason ->
            val first = listOf(
                LogoutStorePolicy.destroyEncryptedDatabase(reason),
                LogoutStorePolicy.wipeOrdinaryMediaCache(reason),
                LogoutStorePolicy.wipeCoilDiskCache(reason),
                LogoutStorePolicy.wipeInFlightAttachmentTransfers(reason),
                LogoutStorePolicy.cancelSenderKeyRetryWork(reason)
            )
            val second = listOf(
                LogoutStorePolicy.destroyEncryptedDatabase(reason),
                LogoutStorePolicy.wipeOrdinaryMediaCache(reason),
                LogoutStorePolicy.wipeCoilDiskCache(reason),
                LogoutStorePolicy.wipeInFlightAttachmentTransfers(reason),
                LogoutStorePolicy.cancelSenderKeyRetryWork(reason)
            )
            assertEquals(reason.name, first, second)
        }
    }

    @Test
    fun firstLoginWithNoPreviousSessionKeepsLocalData() {
        assertEqualsKeep(null, "user-b")
    }

    @Test
    fun differentAccountsPurgeRegardlessOfOrder() {
        assertEqualsPurge("user-b", "user-a")
    }

    private fun assertEqualsKeep(previous: String?, next: String) {
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
