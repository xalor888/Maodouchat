package com.maodouchat.messaging.v2

/** Receiver-side authorization for mutations carried by the blind v2 relay. */
object MessagingV2MutationAuthority {
    fun canApply(
        action: String,
        targetSenderUserId: String,
        envelopeSenderUserId: String,
        envelopeSenderDeviceId: Int,
        envelopeKind: String,
    ): Boolean {
        if (
            action !in setOf(
                MessagingV2EventAction.EDIT,
                MessagingV2EventAction.REVOKE,
                MessagingV2EventAction.DELETE,
            )
        ) return true

        if (targetSenderUserId == envelopeSenderUserId) return true
        return action == MessagingV2EventAction.DELETE &&
            envelopeKind == "SERVICE" &&
            envelopeSenderDeviceId == 0 &&
            envelopeSenderUserId == "system"
    }
}
