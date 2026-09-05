package com.maodouchat.ai.agent.provider

import android.content.Context
import com.maodouchat.ai.agent.AgentChatMessage
import com.maodouchat.ai.agent.LocalAiProvider
import com.maodouchat.ai.agent.LocalAiProviderStore
import com.maodouchat.ai.agent.OpenAiCompatClient

/**
 * Provider interface representing an AI model endpoint abstraction.
 */
interface AiProvider {
    val id: String
    val name: String
    val model: String
    val supportsVision: Boolean

    suspend fun complete(
        messages: List<AgentChatMessage>,
        tools: List<Map<String, Any?>>?,
        onDelta: ((String) -> Unit)? = null
    ): OpenAiCompatClient.Completion
}

/**
 * Default implementation of [AiProvider] backed by [LocalAiProvider].
 */
class LocalAiModelProvider(
    val config: LocalAiProvider
) : AiProvider {
    override val id: String get() = config.id
    override val name: String get() = config.name
    override val model: String get() = config.model
    override val supportsVision: Boolean get() = config.hasVisionCapability()

    override suspend fun complete(
        messages: List<AgentChatMessage>,
        tools: List<Map<String, Any?>>?,
        onDelta: ((String) -> Unit)?
    ): OpenAiCompatClient.Completion {
        return OpenAiCompatClient.complete(config, messages, tools, onDelta)
    }
}

/**
 * Storage and credential manager interface for AI Providers.
 */
interface AiCredentialStore {
    fun getActiveProvider(): LocalAiProvider?
    fun listProviders(): List<LocalAiProvider>
    fun upsertProvider(provider: LocalAiProvider): LocalAiProvider
    fun deleteProvider(id: String)
    fun setActive(id: String)
}

/**
 * Default [AiCredentialStore] backed by [LocalAiProviderStore] and Android Context.
 */
class ContextAiCredentialStore(
    private val context: Context
) : AiCredentialStore {
    override fun getActiveProvider(): LocalAiProvider? = LocalAiProviderStore.activeProvider(context)
    override fun listProviders(): List<LocalAiProvider> = LocalAiProviderStore.listProviders(context)
    override fun upsertProvider(provider: LocalAiProvider): LocalAiProvider =
        LocalAiProviderStore.upsertProvider(context, provider)
    override fun deleteProvider(id: String) = LocalAiProviderStore.deleteProvider(context, id)
    override fun setActive(id: String) = LocalAiProviderStore.setActive(context, id)
}
