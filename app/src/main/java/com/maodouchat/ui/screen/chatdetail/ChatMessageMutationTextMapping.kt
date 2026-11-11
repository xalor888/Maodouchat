package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.domain.messaging.ChatMessageMutationText

/** domain 侧 ChatMessageMutationText 到 app 字符串资源的映射。 */
internal fun chatMessageMutationTextResId(message: ChatMessageMutationText): Int = when (message) {
    ChatMessageMutationText.FEATURE_DISABLED -> R.string.feature_disabled_by_admin
    ChatMessageMutationText.SESSION_EXPIRED -> R.string.error_session_expired
    ChatMessageMutationText.DELETE_SERVER_FAILED -> R.string.chat_delete_server_failed
    ChatMessageMutationText.MESSAGE_REVOKED_PLACEHOLDER -> R.string.chat_message_revoked_placeholder
    ChatMessageMutationText.REVOKE_FAILED -> R.string.chat_revoke_failed
    ChatMessageMutationText.EDIT_EMPTY -> R.string.chat_edit_empty
    ChatMessageMutationText.EDIT_NOT_ALLOWED -> R.string.chat_edit_not_allowed
    ChatMessageMutationText.EDIT_FAILED -> R.string.chat_edit_failed
    ChatMessageMutationText.MUTATION_COMMITTED_LOCAL_SYNC_PENDING -> R.string.chat_mutation_committed_local_sync_pending
    ChatMessageMutationText.REACTION_FAILED -> R.string.chat_reaction_failed
}
