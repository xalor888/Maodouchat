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

    @Test
    fun clearedStateHasNoReadinessAndRequiresFullInitialization() {
        val cleared = SignalInitializationPolicy.cleared()

        assertEquals(null, cleared.accountId)
        assertFalse(cleared.localCryptoReady)
        assertFalse(cleared.publicationReady)
        assertEquals(
            SignalInitializationAction.FULL_INITIALIZATION,
            SignalInitializationPolicy.action(cleared, "u1"),
        )
    }

    @Test
    fun publicationSucceededRequiresLocalCryptoAndKnownAccount() {
        val noLocalCrypto = SignalInitializationPolicy.publicationSucceeded(
            SignalInitializationState(accountId = "u1", localCryptoReady = false),
        )
        assertFalse(noLocalCrypto.publicationReady)

        val anonymous = SignalInitializationPolicy.publicationSucceeded(
            SignalInitializationState(accountId = null, localCryptoReady = true),
        )
        assertFalse(anonymous.publicationReady)
    }

    @Test
    fun reselectingSameAccountDropsReadinessButKeepsAccountId() {
        val ready = SignalInitializationState(
            accountId = "u1",
            localCryptoReady = true,
            publicationReady = true,
        )

        val reselected = SignalInitializationPolicy.selectAccount(ready, "u1")

        assertEquals("u1", reselected.accountId)
        assertFalse(reselected.localCryptoReady)
        assertFalse(reselected.publicationReady)
    }

    @Test
    fun anonymousOrBlankRequestNeverReusesEvenWhenFullyReady() {
        val fullyReady = SignalInitializationState(
            accountId = "u1",
            localCryptoReady = true,
            publicationReady = true,
        )

        assertEquals(
            SignalInitializationAction.FULL_INITIALIZATION,
            SignalInitializationPolicy.action(fullyReady, null),
        )
        assertEquals(
            SignalInitializationAction.FULL_INITIALIZATION,
            SignalInitializationPolicy.action(fullyReady, ""),
        )
    }

    @Test
    fun nonInvalidatingPublicationFailureKeepsLocalReadinessAndRequestsUploadOnly() {
        val ready = SignalInitializationState(
            accountId = "u1",
            localCryptoReady = true,
            publicationReady = false,
        )

        val failed = SignalInitializationPolicy.publicationFailed(ready, invalidateLocalCrypto = false)

        assertEquals("u1", failed.accountId)
        assertTrue(failed.localCryptoReady)
        assertEquals(
            SignalInitializationAction.UPLOAD_ONLY,
            SignalInitializationPolicy.action(failed, "u1"),
        )
    }

    @Test
    fun fullyReadyStateStillRequiresFullInitializationForAnotherAccount() {
        val readyU1 = SignalInitializationState(
            accountId = "u1",
            localCryptoReady = true,
            publicationReady = true,
        )

        assertEquals(
            SignalInitializationAction.FULL_INITIALIZATION,
            SignalInitializationPolicy.action(readyU1, "u2"),
        )
        assertFalse(SignalInitializationPolicy.canReuse("u1", true, "u2"))
        assertFalse(SignalInitializationPolicy.canUseLocalCrypto("u1", true, "u2"))
    }

    @Test
    fun canReuseRejectsBlankRequestedUserId() {
        assertFalse(SignalInitializationPolicy.canReuse("u1", true, "   "))
        assertFalse(SignalInitializationPolicy.canUseLocalCrypto("u1", true, "   "))
    }

    @Test
    fun selectAccountWithBlankRequestedIdResetsToFreshState() {
        val ready = SignalInitializationState(
            accountId = "u1",
            localCryptoReady = true,
            publicationReady = true,
        )

        val reselected = SignalInitializationPolicy.selectAccount(ready, "")

        assertEquals("", reselected.accountId)
        assertFalse(reselected.localCryptoReady)
        assertFalse(reselected.publicationReady)
        assertEquals(
            SignalInitializationAction.FULL_INITIALIZATION,
            SignalInitializationPolicy.action(reselected, "u1"),
        )
    }

    @Test
    fun anonymousPublicationFailureKeepsLocalCryptoButNeverReuses() {
        val anonymous = SignalInitializationPolicy.publicationFailed(
            SignalInitializationState(accountId = null, localCryptoReady = true),
        )

        assertEquals(null, anonymous.accountId)
        assertTrue(anonymous.localCryptoReady)
        assertFalse(anonymous.publicationReady)
        assertEquals(
            SignalInitializationAction.FULL_INITIALIZATION,
            SignalInitializationPolicy.action(anonymous, "u1"),
        )
    }

    @Test
    fun selectAccountToNullFromEmptyStaysEmpty() {
        val selected = SignalInitializationPolicy.selectAccount(
            SignalInitializationPolicy.cleared(),
            null,
        )

        assertEquals(null, selected.accountId)
        assertFalse(selected.localCryptoReady)
        assertFalse(selected.publicationReady)
    }

    @Test
    fun nullAccountAgainstNullRequestFallsThroughToFullInitialization() {
        assertEquals(
            SignalInitializationAction.FULL_INITIALIZATION,
            SignalInitializationPolicy.action(SignalInitializationState(), null),
        )
    }

    @Test
    fun canReuseAndLocalCryptoRejectNullCurrentAndNullRequested() {
        assertFalse(SignalInitializationPolicy.canReuse(null, true, null))
        assertFalse(SignalInitializationPolicy.canUseLocalCrypto(null, true, null))
    }

    @Test
    fun publicationSucceededPreservesAccountAndLocalReadiness() {
        val published = SignalInitializationPolicy.publicationSucceeded(
            SignalInitializationState(accountId = "u1", localCryptoReady = true),
        )

        assertEquals("u1", published.accountId)
        assertTrue(published.localCryptoReady)
        assertTrue(published.publicationReady)
    }

    @Test
    fun localStoreReadyDoesNotTouchPublicationReadiness() {
        val restored = SignalInitializationPolicy.localStoreReady(
            SignalInitializationState(
                accountId = "u1",
                localCryptoReady = false,
                publicationReady = true,
            ),
        )

        assertEquals("u1", restored.accountId)
        assertTrue(restored.localCryptoReady)
        assertTrue(restored.publicationReady)
    }

    @Test
    fun doublePublicationFailureIsIdempotent() {
        val failed = SignalInitializationPolicy.publicationFailed(
            SignalInitializationState(accountId = "u1", localCryptoReady = true),
        )
        val failedAgain = SignalInitializationPolicy.publicationFailed(failed)

        assertEquals("u1", failedAgain.accountId)
        assertTrue(failedAgain.localCryptoReady)
        assertFalse(failedAgain.publicationReady)
    }

    @Test
    fun actionWithLocalReadyButDifferentAccountSkipsUploadOnly() {
        val localReady = SignalInitializationState(
            accountId = "u1",
            localCryptoReady = true,
            publicationReady = false,
        )
        assertEquals(
            SignalInitializationAction.FULL_INITIALIZATION,
            SignalInitializationPolicy.action(localReady, "u2"),
        )
    }

    @Test
    fun publicationFailedInvalidatingKeepsAccountId() {
        val failed = SignalInitializationPolicy.publicationFailed(
            SignalInitializationState(
                accountId = "u1",
                localCryptoReady = true,
                publicationReady = true,
            ),
            invalidateLocalCrypto = true,
        )
        assertEquals("u1", failed.accountId)
        assertFalse(failed.localCryptoReady)
        assertFalse(failed.publicationReady)
    }

    @Test
    fun publicationSucceededTreatsBlankAccountAsKnown() {
        // 实现只判 accountId != null，纯空白账号仍被视为已知
        val published = SignalInitializationPolicy.publicationSucceeded(
            SignalInitializationState(accountId = "   ", localCryptoReady = true),
        )
        assertTrue(published.publicationReady)
    }

    @Test
    fun publicationFailedInvalidatingOnAnonymousKeepsNullAccount() {
        val failed = SignalInitializationPolicy.publicationFailed(
            SignalInitializationState(accountId = null, localCryptoReady = true),
            invalidateLocalCrypto = true,
        )
        assertEquals(null, failed.accountId)
        assertFalse(failed.localCryptoReady)
        assertFalse(failed.publicationReady)
    }

    @Test
    fun nullCurrentUserNeverReusesOrUsesLocalCrypto() {
        assertFalse(SignalInitializationPolicy.canReuse(null, true, "u1"))
        assertFalse(SignalInitializationPolicy.canUseLocalCrypto(null, true, "u1"))
    }
}
