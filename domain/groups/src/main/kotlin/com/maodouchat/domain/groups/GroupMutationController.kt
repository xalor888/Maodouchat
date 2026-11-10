package com.maodouchat.domain.groups

import com.maodouchat.core.model.GroupDetailUiState
import com.maodouchat.core.model.GroupLifecycleService
import com.maodouchat.core.model.GroupMutationAction
import com.maodouchat.core.model.GroupMutationFeedback
import com.maodouchat.core.model.GroupMutationFeedbackKind
import com.maodouchat.core.model.GroupMutationCommit
import com.maodouchat.core.model.GroupMutationFeedbackPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 群变更向 UI 写入的文案种类。domain 侧只定种类，真正的本地化字符串由 app 侧
 * groupMutationMessageResId 映射提供——domain 模块不引用 app 的 R。
 */
enum class GroupMutationMessage {
    GROUP_NAME_UPDATED,
    ANNOUNCEMENT_CLEARED,
    ANNOUNCEMENT_UPDATED,
    NICKNAME_UPDATED,
    MEMBER_ADDED,
    MEMBER_REMOVED,
    ROLE_UPDATED,
    TRANSFER_SUCCESS,
    TITLE_UPDATED,
    MUTE_SET,
    MUTE_CLEARED,
    MUTE_ALL_SET,
    MUTE_ALL_CLEARED,
    SESSION_EXPIRED,
    OPERATION_FAILED,
    IMAGE_PROCESS_FAILED,
    AVATAR_UPDATED,
    AVATAR_FAILED,
}

class GroupMutationController(
    private val scope: CoroutineScope,
    private val currentState: () -> GroupDetailUiState,
    private val updateState: ((GroupDetailUiState) -> GroupDetailUiState) -> Unit,
    private val text: (GroupMutationMessage, Array<out Any>) -> String,
    private val chatId: () -> String,
    private val token: () -> String,
    private val ownerUserId: () -> String,
    private val sessionActive: (String) -> Boolean,
    private val encodeAvatar: suspend (String) -> String?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val groupLifecycleService: GroupLifecycleService,
    private val onCommitted: (String?) -> Unit,
    // pendingRetry 由 VM 持有，经 set lambda 共享。
    private val pendingRetrySet: ((() -> Unit)?) -> Unit,
) {
    fun renameGroup(name: String) {
        val trimmed = name.trim()
        updateGroup(
            action = GroupMutationAction.RENAME,
            successMessage = string(GroupMutationMessage.GROUP_NAME_UPDATED),
            retry = { renameGroup(trimmed) }
        ) { groupLifecycleService.updateGroupInfo(chatId(), name = trimmed, announcement = null, avatar = null) }
    }

    fun updateAnnouncement(announcement: String) {
        val trimmed = announcement.trim()
        updateGroup(
            action = GroupMutationAction.ANNOUNCEMENT,
            successMessage = if (trimmed.isBlank()) {
                string(GroupMutationMessage.ANNOUNCEMENT_CLEARED)
            } else {
                string(GroupMutationMessage.ANNOUNCEMENT_UPDATED)
            },
            retry = { updateAnnouncement(trimmed) }
        ) { groupLifecycleService.updateGroupInfo(chatId(), name = null, announcement = trimmed, avatar = null) }
    }

    fun setMyNickname(nickname: String) {
        val trimmed = nickname.trim()
        updateGroup(
            action = GroupMutationAction.NICKNAME,
            successMessage = string(GroupMutationMessage.NICKNAME_UPDATED),
            retry = { setMyNickname(trimmed) }
        ) { groupLifecycleService.updateMyNickname(chatId(), trimmed) }
    }

    fun addMember(userId: String) {
        updateGroup(
            action = GroupMutationAction.ADD_MEMBER,
            successMessage = string(GroupMutationMessage.MEMBER_ADDED),
            retry = { addMember(userId) }
        ) { groupLifecycleService.addMembers(chatId(), listOf(userId)) }
    }

    fun removeMember(userId: String) {
        if (userId == ownerUserId()) return
        updateGroup(
            action = GroupMutationAction.REMOVE_MEMBER,
            successMessage = string(GroupMutationMessage.MEMBER_REMOVED),
            retry = { removeMember(userId) }
        ) { groupLifecycleService.removeMember(chatId(), userId) }
    }

    fun updateRole(userId: String, role: String) {
        updateGroup(
            action = GroupMutationAction.ROLE,
            successMessage = string(GroupMutationMessage.ROLE_UPDATED),
            retry = { updateRole(userId, role) }
        ) { groupLifecycleService.setRole(chatId(), userId, role) }
    }

    fun transferOwnership(userId: String) {
        if (!currentState().isOwner || userId == ownerUserId()) return
        updateGroup(
            action = GroupMutationAction.TRANSFER_OWNER,
            successMessage = string(GroupMutationMessage.TRANSFER_SUCCESS),
            retry = { transferOwnership(userId) }
        ) { groupLifecycleService.transferOwnership(chatId(), userId) }
    }

    fun updateTitle(userId: String, title: String) {
        val trimmed = title.trim()
        updateGroup(
            action = GroupMutationAction.TITLE,
            successMessage = string(GroupMutationMessage.TITLE_UPDATED),
            retry = { updateTitle(userId, trimmed) }
        ) { groupLifecycleService.setTitle(chatId(), userId, trimmed) }
    }

    fun updateMemberMute(userId: String, mutedUntil: Long) {
        updateGroup(
            action = GroupMutationAction.MUTE,
            successMessage = if (mutedUntil > System.currentTimeMillis()) {
                string(GroupMutationMessage.MUTE_SET)
            } else {
                string(GroupMutationMessage.MUTE_CLEARED)
            },
            retry = { updateMemberMute(userId, mutedUntil) }
        ) { groupLifecycleService.setMemberMute(chatId(), userId, mutedUntil) }
    }

    /** 0.99：全员静音（除群主/管理员）。 */
    fun muteAllMembers(mutedUntil: Long) {
        updateGroup(
            action = GroupMutationAction.MUTE,
            successMessage = if (mutedUntil > System.currentTimeMillis()) {
                string(GroupMutationMessage.MUTE_ALL_SET)
            } else {
                string(GroupMutationMessage.MUTE_ALL_CLEARED)
            },
            retry = { muteAllMembers(mutedUntil) }
        ) { groupLifecycleService.setMuteAll(chatId(), muted = mutedUntil > System.currentTimeMillis()) }
    }

    private fun updateGroup(
        action: GroupMutationAction,
        successMessage: String,
        retry: (() -> Unit)? = null,
        mutation: suspend () -> GroupMutationCommit
    ) {
        if (currentState().isUpdating) return
        if (chatId().isBlank() || token().isBlank()) {
            updateState {
                it.copy(
                    isUpdating = false,
                    message = string(GroupMutationMessage.SESSION_EXPIRED),
                    feedback = GroupMutationFeedbackPolicy.fromThrowable(
                        action,
                        IllegalStateException(string(GroupMutationMessage.SESSION_EXPIRED))
                    )
                )
            }
            return
        }
        val mutationOwnerUserId = ownerUserId()
        scope.launch {
            updateState { it.copy(isUpdating = true, message = null, feedback = null) }
            try {
                if (!sessionActive(mutationOwnerUserId)) {
                    updateState {
                        it.copy(
                            isUpdating = false,
                            message = string(GroupMutationMessage.SESSION_EXPIRED),
                            feedback = GroupMutationFeedbackPolicy.fromThrowable(
                                action,
                                IllegalStateException(string(GroupMutationMessage.SESSION_EXPIRED))
                            )
                        )
                    }
                    return@launch
                }
                val result = withContext(ioDispatcher) {
                    try {
                        Result.success(mutation())
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                }
                result.fold(
                    onSuccess = { commit ->
                        if (!sessionActive(mutationOwnerUserId)) {
                            updateState { it.copy(isUpdating = false) }
                            return@fold
                        }
                        pendingRetrySet(null)
                        updateState { state ->
                            val refreshed = commit.refreshedChat
                            state.copy(
                                groupName = refreshed?.groupName ?: state.groupName,
                                groupAnnouncement = refreshed?.groupAnnouncement ?: state.groupAnnouncement,
                                groupAvatar = refreshed?.groupAvatar ?: state.groupAvatar,
                                memberRevision = refreshed?.memberRevision
                                    ?.takeIf { it > 0L }
                                    ?: state.memberRevision,
                                isUpdating = false,
                                message = successMessage,
                                feedback = GroupMutationFeedbackPolicy.success(action, successMessage),
                            )
                        }
                        onCommitted(successMessage)
                    },
                    onFailure = { error ->
                        val fb = GroupMutationFeedbackPolicy.fromThrowable(action, error)
                        // Keep error dialog visible; permission/conflict offer explicit reload, not silent wipe.
                        pendingRetrySet(if (fb.canRetry) retry else null)
                        updateState {
                            it.copy(
                                isUpdating = false,
                                message = fb.detail ?: string(GroupMutationMessage.OPERATION_FAILED),
                                feedback = fb
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                updateState { it.copy(isUpdating = false) }
                throw error
            }
        }
    }

    fun uploadGroupAvatar(uri: String) {
        if (!currentState().canManageGroup) return
        if (token().isBlank() || chatId().isBlank()) {
            updateState {
                it.copy(
                    isUploadingAvatar = false,
                    message = string(GroupMutationMessage.SESSION_EXPIRED),
                    feedback = GroupMutationFeedbackPolicy.fromThrowable(
                        GroupMutationAction.AVATAR,
                        IllegalStateException(string(GroupMutationMessage.SESSION_EXPIRED))
                    )
                )
            }
            return
        }
        val avatarOwnerUserId = ownerUserId()
        scope.launch {
            updateState { it.copy(isUploadingAvatar = true, message = null, feedback = null) }
            try {
                if (!sessionActive(avatarOwnerUserId)) {
                    updateState {
                        it.copy(
                            isUploadingAvatar = false,
                            message = string(GroupMutationMessage.SESSION_EXPIRED),
                            feedback = GroupMutationFeedbackPolicy.fromThrowable(
                                GroupMutationAction.AVATAR,
                                IllegalStateException(string(GroupMutationMessage.SESSION_EXPIRED))
                            )
                        )
                    }
                    return@launch
                }
                val base64 = withContext(ioDispatcher) {
                    encodeAvatar(uri)
                }
                if (base64 == null) {
                    pendingRetrySet { uploadGroupAvatar(uri) }
                    val fb = GroupMutationFeedback(
                        kind = GroupMutationFeedbackKind.ERROR_RETRYABLE,
                        action = GroupMutationAction.AVATAR,
                        detail = string(GroupMutationMessage.IMAGE_PROCESS_FAILED),
                        canRetry = true,
                        shouldReload = false
                    )
                    updateState {
                        it.copy(isUploadingAvatar = false, message = fb.detail, feedback = fb)
                    }
                    return@launch
                }
                if (!sessionActive(avatarOwnerUserId)) {
                    updateState {
                        it.copy(
                            isUploadingAvatar = false,
                            message = string(GroupMutationMessage.SESSION_EXPIRED),
                            feedback = GroupMutationFeedbackPolicy.fromThrowable(
                                GroupMutationAction.AVATAR,
                                IllegalStateException(string(GroupMutationMessage.SESSION_EXPIRED))
                            )
                        )
                    }
                    return@launch
                }
                groupLifecycleService.uploadAvatar(chatId(), base64).fold(
                    onSuccess = { url ->
                        if (!sessionActive(avatarOwnerUserId)) {
                            return@fold
                        }
                        pendingRetrySet(null)
                        updateState { it.copy(groupAvatar = url, isUploadingAvatar = false) }
                        onCommitted(string(GroupMutationMessage.AVATAR_UPDATED))
                    },
                    onFailure = { error ->
                        val fb = GroupMutationFeedbackPolicy.fromThrowable(GroupMutationAction.AVATAR, error)
                        pendingRetrySet { uploadGroupAvatar(uri) }
                        updateState {
                            it.copy(
                                isUploadingAvatar = false,
                                message = fb.detail ?: string(GroupMutationMessage.AVATAR_FAILED),
                                feedback = fb
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                updateState { it.copy(isUploadingAvatar = false) }
                throw error
            }
        }
    }

    private fun string(message: GroupMutationMessage, vararg args: Any): String =
        text(message, args)
}
