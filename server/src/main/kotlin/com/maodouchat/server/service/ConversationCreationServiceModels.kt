package com.maodouchat.server.service

internal sealed interface NormalizedCommand {
    data class Valid(
        val chatType: String,
        val groupName: String?,
        val allParticipantIds: List<String>,
        val inviteeIds: List<String>,
    ) : NormalizedCommand

    data class Rejected(val outcome: CreateConversationOutcome) : NormalizedCommand {
        constructor(result: CreateConversationResult) : this(CreateConversationOutcome(result))
    }
}

internal class CreationAbort(val outcome: CreateConversationOutcome) : RuntimeException()
