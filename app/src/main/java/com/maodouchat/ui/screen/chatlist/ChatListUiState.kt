package com.maodouchat.ui.screen.chatlist

import com.maodouchat.data.model.Chat
import com.maodouchat.network.ApiException
import kotlinx.coroutines.flow.firstOrNull


internal const val GROUP_OWNER_TRANSFER_REQUIRED = "GROUP_OWNER_TRANSFER_REQUIRED"

internal fun requiresGroupOwnershipTransfer(error: Throwable?): Boolean =
    (error as? ApiException)?.serverCode == GROUP_OWNER_TRANSFER_REQUIRED

/** Notification / nudge sender label: nickname → name → truncated id, never a blank title. */
internal fun listSenderLabel(chat: Chat?, senderId: String, unknownLabel: String = ""): String {
    val fromParticipant = chat?.participants
        ?.firstOrNull { it.id == senderId }
        ?.displayName
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val truncated = com.maodouchat.ui.screen.chatdetail.truncatedSenderId(senderId)
    return fromParticipant ?: truncated ?: unknownLabel.ifBlank { senderId }
}
