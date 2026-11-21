package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.local.entity.AiOperationEntity
import com.maodouchat.domain.messaging.PendingAiAction
import com.maodouchat.domain.messaging.describeAiOperation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

internal object ChatAiOperationFactory {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun prepare(
        action: PendingAiAction,
        ownerUserId: String,
        chatId: String,
        now: Long = System.currentTimeMillis(),
        operationId: String = "aiop_${UUID.randomUUID()}"
    ): AiOperationPreparation {
        val descriptor = describeAiOperation(action) ?: return AiOperationPreparation.Untracked
        if (ownerUserId.isBlank() || chatId.isBlank()) return AiOperationPreparation.InvalidContext
        return AiOperationPreparation.Persisted(
            AiOperationEntity(
                id = operationId,
                ownerUserId = ownerUserId,
                chatId = chatId,
                type = descriptor.type,
                targetMessageId = descriptor.targetMessageId,
                parametersJson = json.encodeToString(descriptor.parameters),
                createdAt = now,
                updatedAt = now
            )
        )
    }

}
