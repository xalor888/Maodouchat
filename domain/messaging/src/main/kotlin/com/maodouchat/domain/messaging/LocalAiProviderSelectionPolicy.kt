package com.maodouchat.domain.messaging

import com.maodouchat.core.model.LocalAiProvider

// 用户自配模型端点列表的选中/增删纯规则；SharedPreferences 读写仍在 app 侧。
object LocalAiProviderSelectionPolicy {
    fun resolveActive(providers: List<LocalAiProvider>, activeId: String?): LocalAiProvider? {
        if (providers.isEmpty()) return null
        return providers.firstOrNull { it.id == activeId } ?: providers.first()
    }

    fun upsert(providers: List<LocalAiProvider>, provider: LocalAiProvider): List<LocalAiProvider> =
        providers.filterNot { it.id == provider.id } + provider

    fun delete(providers: List<LocalAiProvider>, id: String): List<LocalAiProvider> =
        providers.filterNot { it.id == id }

    fun activeIdAfterDelete(
        next: List<LocalAiProvider>,
        deletedId: String,
        activeId: String?,
    ): String? {
        if (activeId != deletedId) return activeId
        return next.firstOrNull()?.id.orEmpty()
    }
}
