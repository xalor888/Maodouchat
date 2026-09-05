package com.maodouchat.settings.policy

import com.maodouchat.settings.model.MultiDevicePreferences
import com.maodouchat.settings.model.PreferencesPatch

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

/**
 * Pure domain policy governing multi-device roaming preferences versioning and conflict resolution.
 */
object PreferenceConflictPolicy {

    /**
     * Resolves differences between current local preferences and incoming remote preferences.
     *
     * @param sessionOwnerUserId The authenticated user ID for the current active session.
     * @param local Current local versioned preferences.
     * @param remote Incoming remote versioned preferences from server.
     * @param pendingLocalPatch Optional uncommitted local user edits not yet pushed to the server.
     * @param currentTimestampMs Current clock epoch milliseconds.
     */
    fun resolve(
        sessionOwnerUserId: String,
        local: MultiDevicePreferences,
        remote: MultiDevicePreferences,
        pendingLocalPatch: PreferencesPatch? = null,
        currentTimestampMs: Long = System.currentTimeMillis(),
    ): ConflictResolutionResult {
        // 1. Session & Account Isolation Guard
        if (sessionOwnerUserId.isNotBlank()) {
            if (remote.ownerUserId.isNotBlank() && remote.ownerUserId != sessionOwnerUserId) {
                return ConflictResolutionResult.AccountMismatchRejected(
                    localOwner = sessionOwnerUserId,
                    remoteOwner = remote.ownerUserId,
                )
            }
            if (local.ownerUserId.isNotBlank() && local.ownerUserId != sessionOwnerUserId) {
                return ConflictResolutionResult.AccountMismatchRejected(
                    localOwner = sessionOwnerUserId,
                    remoteOwner = local.ownerUserId,
                )
            }
        }

        // 2. If there are no pending local edits
        if (pendingLocalPatch == null || pendingLocalPatch.isEmpty) {
            return if (remote.revision > local.revision) {
                ConflictResolutionResult.AcceptedRemote(remote.copy(ownerUserId = sessionOwnerUserId))
            } else if (remote.revision == local.revision) {
                if (remote.updatedAtEpochMs > local.updatedAtEpochMs) {
                    ConflictResolutionResult.AcceptedRemote(remote.copy(ownerUserId = sessionOwnerUserId))
                } else {
                    ConflictResolutionResult.AcceptedRemote(local.copy(ownerUserId = sessionOwnerUserId))
                }
            } else {
                // Local revision is strictly ahead of remote, keep local and require sync push
                ConflictResolutionResult.Merged(
                    mergedPreferences = local.copy(ownerUserId = sessionOwnerUserId),
                    conflictFields = emptyList(),
                    requiresPushToRemote = true,
                )
            }
        }

        // 3. Local has uncommitted pending edits -> Field-level merge
        val conflictFields = mutableListOf<String>()
        val nextRevision = maxOf(local.revision, remote.revision) + 1L

        fun <T> pick(fieldName: String, localValue: T?, remoteValue: T, baseValue: T): T {
            return if (localValue != null && localValue != remoteValue) {
                conflictFields.add(fieldName)
                localValue // Local pending intent preempts remote
            } else {
                remoteValue // Otherwise accept remote's newer state
            }
        }

        val merged = MultiDevicePreferences(
            ownerUserId = sessionOwnerUserId,
            revision = nextRevision,
            updatedAtEpochMs = currentTimestampMs,
            themeMode = pick("themeMode", pendingLocalPatch.themeMode, remote.themeMode, local.themeMode),
            themeStyle = pick("themeStyle", pendingLocalPatch.themeStyle, remote.themeStyle, local.themeStyle),
            accentColor = pick("accentColor", pendingLocalPatch.accentColor, remote.accentColor, local.accentColor),
            languageMode = pick("languageMode", pendingLocalPatch.languageMode, remote.languageMode, local.languageMode),
            chatWallpaper = pick("chatWallpaper", pendingLocalPatch.chatWallpaper, remote.chatWallpaper, local.chatWallpaper),
            chatFontScale = pick("chatFontScale", pendingLocalPatch.chatFontScale, remote.chatFontScale, local.chatFontScale),
            linkPreviewEnabled = pick("linkPreviewEnabled", pendingLocalPatch.linkPreviewEnabled, remote.linkPreviewEnabled, local.linkPreviewEnabled),
            unreadPriorityEnabled = pick("unreadPriorityEnabled", pendingLocalPatch.unreadPriorityEnabled, remote.unreadPriorityEnabled, local.unreadPriorityEnabled),
            writingStyleEnabled = pick("writingStyleEnabled", pendingLocalPatch.writingStyleEnabled, remote.writingStyleEnabled, local.writingStyleEnabled),
            writingStylePreset = pick("writingStylePreset", pendingLocalPatch.writingStylePreset, remote.writingStylePreset, local.writingStylePreset),
            writingStyleCustom = pick("writingStyleCustom", pendingLocalPatch.writingStyleCustom, remote.writingStyleCustom, local.writingStyleCustom),
            appLockTimeoutMinutes = pick("appLockTimeoutMinutes", pendingLocalPatch.appLockTimeoutMinutes, remote.appLockTimeoutMinutes, local.appLockTimeoutMinutes),
            screenSecureEnabled = pick("screenSecureEnabled", pendingLocalPatch.screenSecureEnabled, remote.screenSecureEnabled, local.screenSecureEnabled),
            sensitiveGateEnabled = pick("sensitiveGateEnabled", pendingLocalPatch.sensitiveGateEnabled, remote.sensitiveGateEnabled, local.sensitiveGateEnabled),
        )

        return ConflictResolutionResult.Merged(
            mergedPreferences = merged,
            conflictFields = conflictFields,
            requiresPushToRemote = true,
        )
    }
}
