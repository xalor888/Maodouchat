package com.maodouchat.network.api

import com.maodouchat.network.*
import java.io.File

interface GroupApi {
    suspend fun addGroupMembers(token: String, chatId: String, memberIds: List<String>): Result<Unit>

    suspend fun removeGroupMember(token: String, chatId: String, memberId: String): Result<Unit>

    suspend fun transferGroupOwnership(token: String, chatId: String, memberId: String): Result<Unit>

    suspend fun renameGroup(token: String, chatId: String, newName: String): Result<Unit>

    suspend fun getGroupMembers(token: String, chatId: String): Result<List<GroupMemberDto>>

    suspend fun getSenderKeyDistributionStatus(
    token: String,
    chatId: String,
    epoch: Long? = null,
    currentDeviceId: Int? = null
): Result<SenderKeyDistributionStatusDto>

    suspend fun updateMemberRole(token: String, chatId: String, memberId: String, role: String): Result<Unit>

    suspend fun updateGroupNickname(token: String, chatId: String, nickname: String): Result<Unit>

    suspend fun updateMemberTitle(token: String, chatId: String, memberId: String, title: String): Result<Unit>

    suspend fun updateMemberMute(token: String, chatId: String, memberId: String, mutedUntil: Long): Result<Unit>

    suspend fun muteAllMembers(token: String, chatId: String, mutedUntil: Long): Result<Unit>

    suspend fun updateGroupAnnouncement(token: String, chatId: String, announcement: String): Result<Unit>

    suspend fun getOrCreateGroupInvite(token: String, chatId: String, rotate: Boolean = false, expiresInSeconds: Long = 7L * 24L * 60L * 60L, maxUses: Int = 100): Result<GroupInviteResponse>

    suspend fun getGroupAudit(token: String, chatId: String, limit: Int = 50, offset: Int = 0): Result<List<GroupAuditLogDto>>

    suspend fun joinGroupByInvite(token: String, inviteToken: String): Result<ChatDto>
}


interface KeyDeviceApi {
    suspend fun getSealedSenderCertificate(token: String, deviceId: Int = 1): Result<String>

    suspend fun getDevices(token: String, userId: String, currentDeviceId: Int? = null): Result<List<DeviceInfoDto>>

    suspend fun uploadKeys(token: String, request: UploadKeysRequest): Result<Unit>

    suspend fun getPreKeyBundle(token: String, userId: String): Result<PreKeyBundleDto>

    suspend fun getDevicePreKeyBundle(token: String, userId: String, deviceId: Int): Result<DevicePreKeyBundleDto>

    suspend fun getDevicePreKeyBundles(token: String, userId: String): Result<List<DevicePreKeyBundleDto>>

    suspend fun removeMyDevice(token: String, deviceId: Int): Result<Unit>

    suspend fun renameMyDevice(token: String, deviceId: Int, deviceName: String): Result<Unit>

    suspend fun confirmMyDevice(token: String, deviceId: Int, approverDeviceId: Int, signature: String): Result<Unit>
}


interface CallSignalingApi {
    suspend fun sendSignaling(
    token: String,
    toUserId: String,
    type: String,
    payload: String,
    callId: String = "",
    groupId: String = "",
    groupMemberIds: List<String> = emptyList(),
    groupInvite: Boolean = false,
    epoch: Long = 0,
    sequence: Long = 0,
    idempotencyKey: String = "",
): Result<Unit>

    suspend fun hangUpCall(
    token: String,
    toUserId: String,
    callId: String = "",
    groupId: String = "",
    groupMemberIds: List<String> = emptyList(),
    epoch: Long = 0,
    sequence: Long = 0,
    idempotencyKey: String = "",
): Result<Unit>

    suspend fun getPendingSignaling(token: String, offersOnly: Boolean = false): Result<List<SignalMessageDto>>

    suspend fun getIceConfig(token: String): Result<IceConfigDto>
}


interface MediaUploadApi {
    suspend fun uploadEncryptedAttachment(
    token: String,
    chatId: String,
    messageId: String,
    encryptedFile: File,
    cipherSha256: String,
    onProgress: (Long, Long) -> Unit = { _, _ -> },
    onCheckpoint: suspend (String, Long, Long) -> Unit = { _, _, _ -> }
): Result<AttachmentUploadResponse>

    suspend fun verifyEncryptedAttachmentReady(
    token: String,
    chatId: String,
    messageId: String,
    attachmentId: String,
    expectedSha256: String,
    expectedSize: Long
): Result<AttachmentUploadStatusResponse>

    suspend fun deleteUncommittedAttachment(token: String, attachmentId: String): Result<Unit>

}


interface MediaDownloadApi {
    suspend fun downloadEncryptedAttachment(
    token: String,
    attachmentId: String,
    expectedSha256: String,
    expectedSize: Long,
    target: File,
    onProgress: (Long, Long) -> Unit = { _, _ -> }
): Result<Unit>

    suspend fun downloadPostImage(token: String, imageUrl: String, target: java.io.File): Result<Unit>

}


interface MediaImageApi {
    suspend fun uploadPostImage(token: String, base64Data: String): Result<String>

    suspend fun discardPostImage(token: String, imageUrl: String): Result<Unit>

    suspend fun uploadAvatar(token: String, base64Data: String): Result<String>

    suspend fun removeAvatar(token: String): Result<Unit>

    suspend fun uploadGroupAvatar(token: String, chatId: String, base64Data: String): Result<String>

}


interface MediaApi : MediaUploadApi, MediaDownloadApi, MediaImageApi
