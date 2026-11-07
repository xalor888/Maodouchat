package com.maodouchat.core.model

const val GROUP_OWNER_TRANSFER_REQUIRED = "GROUP_OWNER_TRANSFER_REQUIRED"

fun requiresGroupOwnershipTransfer(error: Throwable?): Boolean =
    (error as? ApiException)?.serverCode == GROUP_OWNER_TRANSFER_REQUIRED

/** Notification / nudge sender label: nickname → name → truncated id, never a blank title. */
fun listSenderLabel(chat: Chat?, senderId: String, unknownLabel: String = ""): String {
    val fromParticipant = chat?.participants
        ?.firstOrNull { it.id == senderId }
        ?.displayName
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val truncated = truncatedSenderId(senderId)
    return fromParticipant ?: truncated ?: unknownLabel.ifBlank { senderId }
}
