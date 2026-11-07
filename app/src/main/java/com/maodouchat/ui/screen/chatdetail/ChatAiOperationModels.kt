package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.local.entity.AiOperationEntity

internal sealed interface AiOperationPreparation {
    data object Untracked : AiOperationPreparation
    data object InvalidContext : AiOperationPreparation
    data class Persisted(val operation: AiOperationEntity) : AiOperationPreparation
}
