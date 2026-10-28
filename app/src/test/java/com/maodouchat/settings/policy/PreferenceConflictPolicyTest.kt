package com.maodouchat.settings.policy

import com.maodouchat.settings.model.MultiDevicePreferences
import com.maodouchat.settings.model.PreferencesPatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceConflictPolicyTest {

    @Test
    fun `account mismatch is rejected to maintain strict session isolation`() {
        val local = MultiDevicePreferences(ownerUserId = "user_1", revision = 2L)
        val remote = MultiDevicePreferences(ownerUserId = "user_2", revision = 5L)

        val result = PreferenceConflictPolicy.resolve(
            sessionOwnerUserId = "user_1",
            local = local,
            remote = remote,
        )

        assertTrue(result is ConflictResolutionResult.AccountMismatchRejected)
        val rejected = result as ConflictResolutionResult.AccountMismatchRejected
        assertEquals("user_1", rejected.localOwner)
        assertEquals("user_2", rejected.remoteOwner)
    }

    @Test
    fun `remote with higher revision is accepted when no pending local edits exist`() {
        val local = MultiDevicePreferences(
            ownerUserId = "user_1",
            revision = 2L,
            themeMode = "light",
            languageMode = "zh-cn",
        )
        val remote = MultiDevicePreferences(
            ownerUserId = "user_1",
            revision = 5L,
            themeMode = "dark",
            languageMode = "en",
        )

        val result = PreferenceConflictPolicy.resolve(
            sessionOwnerUserId = "user_1",
            local = local,
            remote = remote,
            pendingLocalPatch = null,
        )

        assertTrue(result is ConflictResolutionResult.AcceptedRemote)
        val accepted = (result as ConflictResolutionResult.AcceptedRemote).preferences
        assertEquals(5L, accepted.revision)
        assertEquals("dark", accepted.themeMode)
        assertEquals("en", accepted.languageMode)
    }

    @Test
    fun `equal revisions resolve via timestamp tie-breaking`() {
        val local = MultiDevicePreferences(
            ownerUserId = "user_1",
            revision = 3L,
            updatedAtEpochMs = 1000L,
            themeMode = "light",
        )
        val remote = MultiDevicePreferences(
            ownerUserId = "user_1",
            revision = 3L,
            updatedAtEpochMs = 2000L,
            themeMode = "dark",
        )

        val result = PreferenceConflictPolicy.resolve(
            sessionOwnerUserId = "user_1",
            local = local,
            remote = remote,
        )

        assertTrue(result is ConflictResolutionResult.AcceptedRemote)
        val accepted = (result as ConflictResolutionResult.AcceptedRemote).preferences
        assertEquals("dark", accepted.themeMode)
    }

    @Test
    fun `local owner mismatch is rejected carrying session owner as localOwner`() {
        val local = MultiDevicePreferences(ownerUserId = "user_2", revision = 9L)
        val remote = MultiDevicePreferences(ownerUserId = "user_1", revision = 1L)

        val result = PreferenceConflictPolicy.resolve(
            sessionOwnerUserId = "user_1",
            local = local,
            remote = remote,
        )

        assertTrue(result is ConflictResolutionResult.AccountMismatchRejected)
        val rejected = result as ConflictResolutionResult.AccountMismatchRejected
        assertEquals("user_1", rejected.localOwner)
        assertEquals("user_2", rejected.remoteOwner)
    }

    @Test
    fun `remote older than local keeps local and requires push`() {
        val local = MultiDevicePreferences(ownerUserId = "user_1", revision = 5L, themeMode = "light")
        val remote = MultiDevicePreferences(ownerUserId = "user_1", revision = 2L, themeMode = "dark")

        val result = PreferenceConflictPolicy.resolve(
            sessionOwnerUserId = "user_1",
            local = local,
            remote = remote,
        )

        assertTrue(result is ConflictResolutionResult.Merged)
        val merged = result as ConflictResolutionResult.Merged
        assertEquals(5L, merged.mergedPreferences.revision)
        assertEquals("light", merged.mergedPreferences.themeMode)
        assertEquals("user_1", merged.mergedPreferences.ownerUserId)
        assertTrue(merged.requiresPushToRemote)
        assertTrue(merged.conflictFields.isEmpty())
    }

    @Test
    fun `equal revision with older remote timestamp keeps local`() {
        val local = MultiDevicePreferences(
            ownerUserId = "user_1",
            revision = 3L,
            updatedAtEpochMs = 2000L,
            themeMode = "light",
        )
        val remote = MultiDevicePreferences(
            ownerUserId = "user_1",
            revision = 3L,
            updatedAtEpochMs = 1000L,
            themeMode = "dark",
        )

        val result = PreferenceConflictPolicy.resolve(
            sessionOwnerUserId = "user_1",
            local = local,
            remote = remote,
        )

        assertTrue(result is ConflictResolutionResult.AcceptedRemote)
        val accepted = (result as ConflictResolutionResult.AcceptedRemote).preferences
        assertEquals("light", accepted.themeMode)
    }

    @Test
    fun `blank session owner skips isolation guard`() {
        val local = MultiDevicePreferences(ownerUserId = "user_1", revision = 1L)
        val remote = MultiDevicePreferences(ownerUserId = "user_2", revision = 2L)

        val result = PreferenceConflictPolicy.resolve(
            sessionOwnerUserId = "",
            local = local,
            remote = remote,
        )

        // 不触发 AccountMismatchRejected：revision 高的 remote 被接受，owner 归一为 session（空串）
        assertTrue(result is ConflictResolutionResult.AcceptedRemote)
        val accepted = (result as ConflictResolutionResult.AcceptedRemote).preferences
        assertEquals("", accepted.ownerUserId)
    }

    @Test
    fun `pending patch matching remote value records no conflict field`() {
        val local = MultiDevicePreferences(ownerUserId = "user_1", revision = 2L, themeMode = "light")
        val remote = MultiDevicePreferences(ownerUserId = "user_1", revision = 4L, themeMode = "dark")
        val patch = PreferencesPatch(themeMode = "dark")

        val result = PreferenceConflictPolicy.resolve(
            sessionOwnerUserId = "user_1",
            local = local,
            remote = remote,
            pendingLocalPatch = patch,
            currentTimestampMs = 5000L,
        )

        assertTrue(result is ConflictResolutionResult.Merged)
        val merged = result as ConflictResolutionResult.Merged
        assertEquals(5L, merged.mergedPreferences.revision)
        assertEquals("dark", merged.mergedPreferences.themeMode)
        assertTrue(merged.conflictFields.isEmpty())
        assertTrue(merged.requiresPushToRemote)
    }

    @Test
    fun `empty patch is treated as no pending edits`() {
        val local = MultiDevicePreferences(ownerUserId = "user_1", revision = 2L)
        val remote = MultiDevicePreferences(ownerUserId = "user_1", revision = 3L, themeMode = "dark")

        val result = PreferenceConflictPolicy.resolve(
            sessionOwnerUserId = "user_1",
            local = local,
            remote = remote,
            pendingLocalPatch = PreferencesPatch(),
        )

        assertTrue(result is ConflictResolutionResult.AcceptedRemote)
        val accepted = (result as ConflictResolutionResult.AcceptedRemote).preferences
        assertEquals("dark", accepted.themeMode)
    }

    @Test
    fun `local pending edits preempt remote conflicting fields and merge non-conflicting fields`() {
        val local = MultiDevicePreferences(
            ownerUserId = "user_1",
            revision = 2L,
            themeMode = "light",
            chatWallpaper = "pattern_1",
            linkPreviewEnabled = true,
        )
        val remote = MultiDevicePreferences(
            ownerUserId = "user_1",
            revision = 4L,
            themeMode = "dark",
            chatWallpaper = "pattern_2",
            linkPreviewEnabled = false,
        )

        // User locally changed themeMode to "system" before remote sync arrived
        val pendingPatch = PreferencesPatch(
            themeMode = "system",
        )

        val result = PreferenceConflictPolicy.resolve(
            sessionOwnerUserId = "user_1",
            local = local,
            remote = remote,
            pendingLocalPatch = pendingPatch,
            currentTimestampMs = 5000L,
        )

        assertTrue(result is ConflictResolutionResult.Merged)
        val merged = (result as ConflictResolutionResult.Merged).mergedPreferences
        // Revision must increment beyond max(local, remote)
        assertEquals(5L, merged.revision)
        assertEquals(5000L, merged.updatedAtEpochMs)
        // Local edit takes priority for themeMode
        assertEquals("system", merged.themeMode)
        // Remote value is taken for untouched fields
        assertEquals("pattern_2", merged.chatWallpaper)
        assertFalse(merged.linkPreviewEnabled)
        assertTrue(result.requiresPushToRemote)
        assertEquals(listOf("themeMode"), result.conflictFields)
    }

    @Test
    fun `blank local and remote owners skip isolation guard and normalize to session owner`() {
        val local = MultiDevicePreferences(ownerUserId = "", revision = 1L, themeMode = "light")
        val remote = MultiDevicePreferences(ownerUserId = "", revision = 2L, themeMode = "dark")

        val result = PreferenceConflictPolicy.resolve(
            sessionOwnerUserId = "user_1",
            local = local,
            remote = remote,
        )

        assertTrue(result is ConflictResolutionResult.AcceptedRemote)
        val accepted = (result as ConflictResolutionResult.AcceptedRemote).preferences
        assertEquals("user_1", accepted.ownerUserId)
        assertEquals("dark", accepted.themeMode)
    }

    @Test
    fun `blank remote owner does not hide a mismatched local owner`() {
        val local = MultiDevicePreferences(ownerUserId = "user_2", revision = 9L)
        val remote = MultiDevicePreferences(ownerUserId = "", revision = 1L)

        val result = PreferenceConflictPolicy.resolve(
            sessionOwnerUserId = "user_1",
            local = local,
            remote = remote,
        )

        assertTrue(result is ConflictResolutionResult.AccountMismatchRejected)
        val rejected = result as ConflictResolutionResult.AccountMismatchRejected
        assertEquals("user_1", rejected.localOwner)
        assertEquals("user_2", rejected.remoteOwner)
    }

    @Test
    fun `remote mismatch takes precedence over local mismatch`() {
        val local = MultiDevicePreferences(ownerUserId = "user_2", revision = 9L)
        val remote = MultiDevicePreferences(ownerUserId = "user_3", revision = 1L)

        val result = PreferenceConflictPolicy.resolve(
            sessionOwnerUserId = "user_1",
            local = local,
            remote = remote,
        )

        assertTrue(result is ConflictResolutionResult.AccountMismatchRejected)
        val rejected = result as ConflictResolutionResult.AccountMismatchRejected
        assertEquals("user_1", rejected.localOwner)
        assertEquals("user_3", rejected.remoteOwner)
    }

    @Test
    fun `equal revision with equal timestamp keeps local`() {
        val local = MultiDevicePreferences(
            ownerUserId = "user_1",
            revision = 3L,
            updatedAtEpochMs = 1000L,
            themeMode = "light",
        )
        val remote = MultiDevicePreferences(
            ownerUserId = "user_1",
            revision = 3L,
            updatedAtEpochMs = 1000L,
            themeMode = "dark",
        )

        val result = PreferenceConflictPolicy.resolve(
            sessionOwnerUserId = "user_1",
            local = local,
            remote = remote,
        )

        assertTrue(result is ConflictResolutionResult.AcceptedRemote)
        val accepted = (result as ConflictResolutionResult.AcceptedRemote).preferences
        assertEquals("light", accepted.themeMode)
    }

    @Test
    fun `two conflicting pending fields are both recorded in field order`() {
        val local = MultiDevicePreferences(
            ownerUserId = "user_1",
            revision = 2L,
            themeMode = "light",
            languageMode = "zh-cn",
            chatWallpaper = "pattern_1",
        )
        val remote = MultiDevicePreferences(
            ownerUserId = "user_1",
            revision = 4L,
            themeMode = "dark",
            languageMode = "en",
            chatWallpaper = "pattern_2",
        )
        val patch = PreferencesPatch(themeMode = "system", languageMode = "fr")

        val result = PreferenceConflictPolicy.resolve(
            sessionOwnerUserId = "user_1",
            local = local,
            remote = remote,
            pendingLocalPatch = patch,
            currentTimestampMs = 5000L,
        )

        assertTrue(result is ConflictResolutionResult.Merged)
        val merged = result as ConflictResolutionResult.Merged
        assertEquals(5L, merged.mergedPreferences.revision)
        assertEquals("system", merged.mergedPreferences.themeMode)
        assertEquals("fr", merged.mergedPreferences.languageMode)
        assertEquals("pattern_2", merged.mergedPreferences.chatWallpaper)
        assertEquals(listOf("themeMode", "languageMode"), merged.conflictFields)
        assertTrue(merged.requiresPushToRemote)
    }
}
