package com.maodouchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalInitializationPolicyTest {

    @Test
    fun completedSameAccountInitializationIsReused() {
        assertTrue(SignalInitializationPolicy.canReuse("u1", true, "u1"))
    }

    @Test
    fun failedDifferentOrAnonymousInitializationMustRun() {
        assertFalse(SignalInitializationPolicy.canReuse("u1", false, "u1"))
        assertFalse(SignalInitializationPolicy.canReuse("u1", true, "u2"))
        assertFalse(SignalInitializationPolicy.canReuse("u1", true, null))
        assertFalse(SignalInitializationPolicy.canReuse("u1", true, ""))
    }

    @Test
    fun localCryptoReadinessIsIndependentFromUploadReadiness() {
        assertTrue(SignalInitializationPolicy.canUseLocalCrypto("u1", true, "u1"))
        assertFalse(SignalInitializationPolicy.canUseLocalCrypto("u1", false, "u1"))
        assertFalse(SignalInitializationPolicy.canUseLocalCrypto("u1", true, "u2"))
        assertFalse(SignalInitializationPolicy.canUseLocalCrypto("u1", true, null))
    }

    @Test
    fun localRestoreSurvivesPublicationFailure() {
        val selected = SignalInitializationPolicy.selectAccount(
            SignalInitializationState(),
            "u1",
        )
        val restored = SignalInitializationPolicy.localStoreReady(selected)
        val failedUpload = SignalInitializationPolicy.publicationFailed(restored)

        assertEquals("u1", failedUpload.accountId)
        assertTrue(failedUpload.localCryptoReady)
        assertFalse(failedUpload.publicationReady)
    }

    @Test
    fun retryAfterPublicationFailureUploadsOnlyWithoutReplacingRatchetStore() {
        val failedUpload = SignalInitializationState(
            accountId = "u1",
            localCryptoReady = true,
            publicationReady = false,
        )

        val action = SignalInitializationPolicy.action(failedUpload, "u1")

        assertEquals(SignalInitializationAction.UPLOAD_ONLY, action)
        assertFalse(action.rebuildsLocalStore)
    }

    @Test
    fun invalidatingPublicationFailureForcesFullInitialization() {
        val localReady = SignalInitializationState(
            accountId = "u1",
            localCryptoReady = true,
            publicationReady = false,
        )

        val failedUpload = SignalInitializationPolicy.publicationFailed(
            localReady,
            invalidateLocalCrypto = true,
        )

        assertFalse(failedUpload.localCryptoReady)
        assertFalse(failedUpload.publicationReady)
        assertEquals(
            SignalInitializationAction.FULL_INITIALIZATION,
            SignalInitializationPolicy.action(failedUpload, "u1"),
        )
    }

    @Test
    fun successfulPublicationMakesNextSameAccountInitializationReusable() {
        val localReady = SignalInitializationState(
            accountId = "u1",
            localCryptoReady = true,
            publicationReady = false,
        )
        val published = SignalInitializationPolicy.publicationSucceeded(localReady)

        assertTrue(published.publicationReady)
        assertEquals(
            SignalInitializationAction.REUSE,
            SignalInitializationPolicy.action(published, "u1"),
        )
    }

    @Test
    fun accountSwitchClearsBothLocalAndPublicationReadiness() {
        val readyU1 = SignalInitializationState(
            accountId = "u1",
            localCryptoReady = true,
            publicationReady = true,
        )

        val selectedU2 = SignalInitializationPolicy.selectAccount(readyU1, "u2")

        assertEquals("u2", selectedU2.accountId)
        assertFalse(selectedU2.localCryptoReady)
        assertFalse(selectedU2.publicationReady)
        assertEquals(
            SignalInitializationAction.FULL_INITIALIZATION,
            SignalInitializationPolicy.action(readyU1, "u2"),
        )
    }
}
