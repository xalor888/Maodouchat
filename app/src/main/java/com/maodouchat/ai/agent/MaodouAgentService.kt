package com.maodouchat.ai.agent

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.maodouchat.MaodouchatApp
import com.maodouchat.ai.AiPrivacyPreferences
import com.maodouchat.ai.AiWritingStylePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

object MaodouAgentService {
    enum class BallState { IDLE, RUNNING, PENDING_APPROVAL }

    val sessions: SnapshotStateList<AgentSession> = mutableStateListOf()
    val messages: SnapshotStateList<AgentChatMessage> = mutableStateListOf()
    val currentSessionId = mutableStateOf<String?>(null)
    val ballState = mutableStateOf(BallState.IDLE)
    val streamingText = mutableStateOf("")
    val pendingApproval = mutableStateOf<PendingAgentApproval?>(null)
    val errorMessage = mutableStateOf<String?>(null)
    val overlayMode = mutableStateOf(AgentOverlayMode.DISABLED)

    private var turnJob: Job? = null
    private var lastUserText: String = ""

    fun init(context: Context) {
        overlayMode.value = LocalAiProviderStore.overlayMode(context)
        if (sessions.isEmpty()) {
            sessions.clear()
            sessions.addAll(LocalAiProviderStore.loadSessions(context))
            if (sessions.isEmpty()) newSession(context)
            else switchSession(sessions.last().id)
        }
    }

    fun newSession(context: Context) {
        val session = AgentSession(
            id = "s_${UUID.randomUUID()}",
            title = "新对话",
            createdAt = System.currentTimeMillis(),
            messages = emptyList()
        )
        sessions.add(session)
        switchSession(session.id)
        persist(context)
    }

    fun switchSession(id: String) {
        currentSessionId.value = id
        messages.clear()
        messages.addAll(sessions.firstOrNull { it.id == id }?.messages.orEmpty())
        pendingApproval.value = null
        streamingText.value = ""
        ballState.value = BallState.IDLE
    }

    fun send(context: Context, text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        if (!AiPrivacyPreferences.userEnabled(context) || !AiPrivacyPreferences.consentAccepted(context)) {
            errorMessage.value = "请先在设置里打开 AI 并授权本机处理"
            return
        }
        val provider = LocalAiProviderStore.activeProvider(context)
        if (provider == null || provider.apiKey.isBlank()) {
            errorMessage.value = LocalAiGateway.missingProviderMessage()
            return
        }
        lastUserText = trimmed
        messages.add(AgentChatMessage(role = "user", content = trimmed))
        persist(context)
        runEngine(context, provider, approvedCall = null)
    }

    fun approvePending(context: Context) {
        val pending = pendingApproval.value ?: return
        pendingApproval.value = null
        val provider = LocalAiProviderStore.activeProvider(context) ?: return
        runEngine(context, provider, approvedCall = pending.call)
    }

    fun rejectPending(context: Context) {
        val pending = pendingApproval.value ?: return
        pendingApproval.value = null
        messages.add(
            AgentChatMessage(
                role = "tool",
                content = "用户拒绝了 ${pending.call.name}",
                toolCallId = pending.call.id,
                toolName = pending.call.name
            )
        )
        persist(context)
        ballState.value = BallState.IDLE
    }

    fun cancelTurn() {
        turnJob?.cancel()
        turnJob = null
        ballState.value = BallState.IDLE
        pendingApproval.value = null
        streamingText.value = ""
    }

    private fun runEngine(context: Context, provider: LocalAiProvider, approvedCall: AgentToolCall?) {
        turnJob?.cancel()
        ballState.value = BallState.RUNNING
        errorMessage.value = null
        val app = context.applicationContext as? MaodouchatApp ?: MaodouchatApp.instance
        val style = AgentSessionEngine.styleHintFrom(AiWritingStylePreferences.snapshot(context))
        val history = messages.toList().filter { it.role != "system" }
        turnJob = app.applicationScope.launch {
            AgentSessionEngine().runTurn(
                provider = provider,
                history = history.dropLastWhile { it.role == "user" && it.content == lastUserText },
                userText = lastUserText,
                styleHint = style,
                approvedCall = approvedCall
            ).collect { event ->
                withContext(Dispatchers.Main) {
                when (event) {
                    is AgentTurnEvent.TextDelta -> streamingText.value += event.text
                    is AgentTurnEvent.AssistantFinal -> {
                        messages.add(AgentChatMessage(role = "assistant", content = event.text))
                        streamingText.value = ""
                        ballState.value = BallState.IDLE
                        persist(context)
                    }
                    is AgentTurnEvent.ToolStarted -> Unit
                    is AgentTurnEvent.ToolFinished -> {
                        messages.add(
                            AgentChatMessage(
                                role = "tool",
                                content = event.result.take(500),
                                toolName = event.name
                            )
                        )
                    }
                    is AgentTurnEvent.NeedsApproval -> {
                        pendingApproval.value = event.pending
                        ballState.value = BallState.PENDING_APPROVAL
                    }
                    is AgentTurnEvent.Failed -> {
                        errorMessage.value = event.message
                        ballState.value = BallState.IDLE
                    }
                }
                }
            }
        }
    }

    private fun persist(context: Context) {
        val id = currentSessionId.value ?: return
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx < 0) return
        val title = messages.firstOrNull { it.role == "user" }?.content?.take(24) ?: sessions[idx].title
        sessions[idx] = sessions[idx].copy(title = title, messages = messages.toList())
        LocalAiProviderStore.saveSessions(context, sessions.toList())
    }
}
