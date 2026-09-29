package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.group.GroupDetailUiState
import com.maodouchat.group.GroupLifecycleService
import com.maodouchat.group.GroupMutationAction
import com.maodouchat.group.GroupMutationCommit
import com.maodouchat.group.GroupMutationFeedbackPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * G364：群变更一族（改名 / 公告 / 昵称 / 增删成员 / 角色 / 转让 / 头衔 / 禁言，共 10 个公开入口 +
 * `updateGroup` 执行器）从 `GroupDetailViewModel` 抽出（纯搬移不改判断）。
 *
 * `pendingRetry` 由 VM 持有（load / loadGroupInvite / uploadGroupAvatar / redistributeSenderKey
 * 也写它），经 set lambda 共享；提交成功后经 `onCommitted` 回调 VM 的 `load`。
 */
internal class GroupMutationController(
    private val scope: CoroutineScope,
    private val currentState: () -> GroupDetailUiState,
    private val updateState: ((GroupDetailUiState) -> GroupDetailUiState) -> Unit,
    private val textFn: (Int, Array<out Any>) -> String,
    private val chatId: () -> String,
    private val token: () -> String,
    private val ownerUserId: () -> String,
    private val groupLifecycleService: GroupLifecycleService,
    private val onCommitted: (String?) -> Unit,
    private val pendingRetrySet: ((() -> Unit)?) -> Unit,
) {
    fun renameGroup(name: String) {
        val trimmed = name.trim()
        updateGroup(
            action = GroupMutationAction.RENAME,
            successMessage = text(R.string.chat_group_name_updated),
            retry = { renameGroup(trimmed) }
        ) { groupLifecycleService.updateGroupInfo(chatId(), name = trimmed, announcement = null, avatar = null) }
    }

    fun updateAnnouncement(announcement: String) {
        val trimmed = announcement.trim()
        updateGroup(
            action = GroupMutationAction.ANNOUNCEMENT,
            successMessage = if (trimmed.isBlank()) {
                text(R.string.group_detail_announcement_cleared)
            } else {
                text(R.string.chat_group_announcement_updated)
            },
            retry = { updateAnnouncement(trimmed) }
        ) { groupLifecycleService.updateGroupInfo(chatId(), name = null, announcement = trimmed, avatar = null) }
    }

    fun setMyNickname(nickname: String) {
        val trimmed = nickname.trim()
        updateGroup(
            action = GroupMutationAction.NICKNAME,
            successMessage = text(R.string.chat_group_nickname_updated),
            retry = { setMyNickname(trimmed) }
        ) { groupLifecycleService.updateMyNickname(chatId(), trimmed) }
    }

    fun addMember(userId: String) {
        updateGroup(
            action = GroupMutationAction.ADD_MEMBER,
            successMessage = text(R.string.chat_group_member_added_key),
            retry = { addMember(userId) }
        ) { groupLifecycleService.addMembers(chatId(), listOf(userId)) }
    }

    fun removeMember(userId: String) {
        if (userId == ownerUserId()) return
        updateGroup(
            action = GroupMutationAction.REMOVE_MEMBER,
            successMessage = text(R.string.chat_group_member_removed_key),
            retry = { removeMember(userId) }
        ) { groupLifecycleService.removeMember(chatId(), userId) }
    }

    fun updateRole(userId: String, role: String) {
        updateGroup(
            action = GroupMutationAction.ROLE,
            successMessage = text(R.string.chat_group_role_updated),
            retry = { updateRole(userId, role) }
        ) { groupLifecycleService.setRole(chatId(), userId, role) }
    }

    fun transferOwnership(userId: String) {
        if (!currentState().isOwner || userId == ownerUserId()) return
        updateGroup(
            action = GroupMutationAction.TRANSFER_OWNER,
            successMessage = text(R.string.group_detail_transfer_success),
            retry = { transferOwnership(userId) }
        ) { groupLifecycleService.transferOwnership(chatId(), userId) }
    }

    fun updateTitle(userId: String, title: String) {
        val trimmed = title.trim()
        updateGroup(
            action = GroupMutationAction.TITLE,
            successMessage = text(R.string.chat_group_title_updated),
            retry = { updateTitle(userId, trimmed) }
        ) { groupLifecycleService.setTitle(chatId(), userId, trimmed) }
    }

    fun updateMemberMute(userId: String, mutedUntil: Long) {
        updateGroup(
            action = GroupMutationAction.MUTE,
            successMessage = if (mutedUntil > System.currentTimeMillis()) {
                text(R.string.group_detail_mute_set)
            } else {
                text(R.string.group_detail_mute_cleared)
            },
            retry = { updateMemberMute(userId, mutedUntil) }
        ) { groupLifecycleService.setMemberMute(chatId(), userId, mutedUntil) }
    }

    /** 0.99：全员静音（除群主/管理员）。 */
    fun muteAllMembers(mutedUntil: Long) {
        updateGroup(
            action = GroupMutationAction.MUTE,
            successMessage = if (mutedUntil > System.currentTimeMillis()) {
                text(R.string.group_detail_mute_all_set)
            } else {
                text(R.string.group_detail_mute_all_cleared)
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
                    message = text(R.string.error_session_expired),
                    feedback = GroupMutationFeedbackPolicy.fromThrowable(
                        action,
                        IllegalStateException(text(R.string.error_session_expired))
                    )
                )
            }
            return
        }
        val mutationOwnerUserId = ownerUserId()
        scope.launch {
            updateState { it.copy(isUpdating = true, message = null, feedback = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = mutationOwnerUserId,
                )
                ) {
                    updateState {
                        it.copy(
                            isUpdating = false,
                            message = text(R.string.error_session_expired),
                            feedback = GroupMutationFeedbackPolicy.fromThrowable(
                                action,
                                IllegalStateException(text(R.string.error_session_expired))
                            )
                        )
                    }
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
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
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = mutationOwnerUserId,
                        )
                        ) {
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
                                message = fb.detail ?: text(R.string.group_detail_operation_failed),
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

    private fun text(id: Int, vararg args: Any): String = textFn(id, args)
}
