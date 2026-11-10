package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.domain.groups.GroupMutationMessage

/** domain 侧 GroupMutationMessage 到 app 字符串资源的映射。 */
internal fun groupMutationMessageResId(message: GroupMutationMessage): Int = when (message) {
    GroupMutationMessage.GROUP_NAME_UPDATED -> R.string.chat_group_name_updated
    GroupMutationMessage.ANNOUNCEMENT_CLEARED -> R.string.group_detail_announcement_cleared
    GroupMutationMessage.ANNOUNCEMENT_UPDATED -> R.string.chat_group_announcement_updated
    GroupMutationMessage.NICKNAME_UPDATED -> R.string.chat_group_nickname_updated
    GroupMutationMessage.MEMBER_ADDED -> R.string.chat_group_member_added_key
    GroupMutationMessage.MEMBER_REMOVED -> R.string.chat_group_member_removed_key
    GroupMutationMessage.ROLE_UPDATED -> R.string.chat_group_role_updated
    GroupMutationMessage.TRANSFER_SUCCESS -> R.string.group_detail_transfer_success
    GroupMutationMessage.TITLE_UPDATED -> R.string.chat_group_title_updated
    GroupMutationMessage.MUTE_SET -> R.string.group_detail_mute_set
    GroupMutationMessage.MUTE_CLEARED -> R.string.group_detail_mute_cleared
    GroupMutationMessage.MUTE_ALL_SET -> R.string.group_detail_mute_all_set
    GroupMutationMessage.MUTE_ALL_CLEARED -> R.string.group_detail_mute_all_cleared
    GroupMutationMessage.SESSION_EXPIRED -> R.string.error_session_expired
    GroupMutationMessage.OPERATION_FAILED -> R.string.group_detail_operation_failed
    GroupMutationMessage.IMAGE_PROCESS_FAILED -> R.string.settings_image_process_failed
    GroupMutationMessage.AVATAR_UPDATED -> R.string.group_detail_avatar_updated
    GroupMutationMessage.AVATAR_FAILED -> R.string.group_detail_avatar_failed
}
