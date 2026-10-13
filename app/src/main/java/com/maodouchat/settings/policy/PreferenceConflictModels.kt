package com.maodouchat.settings.policy

import com.maodouchat.settings.model.MultiDevicePreferences

/**
 * Outcome of resolving a synchronization conflict between local preferences and remote cloud preferences.
 */
sealed interface ConflictResolutionResult {
    /** Both local and remote are identical or remote was safely accepted without conflicts. */
    data class AcceptedRemote(val preferences: MultiDevicePreferences) : ConflictResolutionResult

    /** Local has uncommitted modifications; fields were merged using last-writer-wins or local priority. */
    data class Merged(
        val mergedPreferences: MultiDevicePreferences,
        val conflictFields: List<String>,
        val requiresPushToRemote: Boolean,
    ) : ConflictResolutionResult

    /** Conflict could not be resolved because accounts do not match (session isolation guard). */
    data class AccountMismatchRejected(val localOwner: String, val remoteOwner: String) : ConflictResolutionResult
}

/**
 * Audit record of a preference conflict event.
 */
data class PreferenceConflictAudit(
    val timestampMs: Long,
    val localRevision: Long,
    val remoteRevision: Long,
    val modifiedFields: List<String>,
    val actionTaken: String,
)
