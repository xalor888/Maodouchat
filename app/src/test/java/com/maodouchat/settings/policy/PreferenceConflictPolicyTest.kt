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
}
