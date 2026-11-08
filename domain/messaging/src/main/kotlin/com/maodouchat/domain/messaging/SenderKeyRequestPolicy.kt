package com.maodouchat.domain.messaging

/** Sender Key 请求准入：只有属主本人被请求、请求来自他人、且 epoch 有效时才处理。 */
fun isOwnSenderKeyRequest(
    ownerUserId: String,
    requestedSenderUserId: String?,
    senderUserId: String,
    groupRevision: Long,
): Boolean =
    ownerUserId.isNotBlank() &&
        requestedSenderUserId == ownerUserId &&
        senderUserId != ownerUserId &&
        groupRevision > 0L
