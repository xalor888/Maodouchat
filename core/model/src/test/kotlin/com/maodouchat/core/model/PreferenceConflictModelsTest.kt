package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceConflictModelsTest {

    @Test
    fun `resolution variants carry their fields`() {
        val accepted = ConflictResolutionResult.AcceptedRemote(
            MultiDevicePreferences(ownerUserId = "u1", revision = 5L),
        )
        assertEquals("u1", accepted.preferences.ownerUserId)
        val merged = ConflictResolutionResult.Merged(
            mergedPreferences = MultiDevicePreferences(),
            conflictFields = listOf("themeMode"),
            requiresPushToRemote = true,
        )
        assertEquals(listOf("themeMode"), merged.conflictFields)
        assertTrue(merged.requiresPushToRemote)
        val rejected = ConflictResolutionResult.AccountMismatchRejected("u1", "u2")
        assertEquals("u1", rejected.localOwner)
        assertEquals("u2", rejected.remoteOwner)
    }

    @Test
    fun `audit carries revisions and action`() {
        val audit = PreferenceConflictAudit(
            timestampMs = 100L,
            localRevision = 2L,
            remoteRevision = 5L,
            modifiedFields = listOf("themeMode"),
            actionTaken = "merged",
        )
        assertEquals(2L, audit.localRevision)
        assertEquals(5L, audit.remoteRevision)
        assertEquals("merged", audit.actionTaken)
    }
}
