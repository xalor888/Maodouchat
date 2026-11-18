package com.maodouchat.ai.agent

import com.maodouchat.core.model.AgentChatMessage
import com.maodouchat.core.model.AgentToolCall
import com.maodouchat.core.model.AiPromptSafetyPolicy
import com.maodouchat.core.model.Approval
import com.maodouchat.core.model.LocalAiCompletion
import com.maodouchat.core.model.LocalAiProvider
import com.maodouchat.domain.messaging.AgentToolHostPolicy
import com.maodouchat.domain.messaging.AgentToolPolicy
import com.maodouchat.domain.messaging.AgentTurnEvent
import com.maodouchat.domain.messaging.AgentTurnMessagePolicy
import com.maodouchat.domain.messaging.AgentTurnMessages
import com.maodouchat.domain.messaging.PendingAgentApproval
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

// 轮次执行器：消息装配走 AgentTurnMessages，文本直连走 AgentTextCompletion。
class AgentSessionEngine(
    private val complete: suspend (
        LocalAiProvider,
        List<AgentChatMessage>,
        List<Map<String, Any?>>?,
        ((String) -> Unit)?
    ) -> LocalAiCompletion = { provider, messages, tools, onDelta ->
        OpenAiCompatClient.complete(provider, messages, tools, onDelta)
    },
    private val executeTool: suspend (String, String) -> String = { name, args ->
        AgentToolHost.execute(name, args)
    }
) {
    fun runTurn(
        provider: LocalAiProvider,
        history: List<AgentChatMessage>,
        userText: String,
        styleHint: String?,
        approvedCall: AgentToolCall? = null
    ): Flow<AgentTurnEvent> = flow {
        val working = AgentTurnMessages.base(provider, history, userText, styleHint)
        if (approvedCall != null) {
            emit(AgentTurnMessages.prefillApprovedCall(working, approvedCall, executeTool))
        }
        var rounds = 0
        while (rounds < AgentToolPolicy.MAX_TOOL_ROUNDS) {
            rounds++
            when (
                val result = complete(
                    provider,
                    working,
                    AgentToolPolicy.openaiToolsJson(),
                    null
                )
            ) {
                is LocalAiCompletion.Error -> {
                    emit(AgentTurnEvent.Failed(result.message))
                    return@flow
                }
                is LocalAiCompletion.Text -> {
                    val text = AiPromptSafetyPolicy.annotateIfPrivilegedHallucination(
                        result.content.trim(),
                        AgentTurnMessagePolicy.PRIVILEGED_HALLUCINATION_NOTE
                    )
                    emit(AgentTurnEvent.AssistantFinal(text))
                    return@flow
                }
                is LocalAiCompletion.Tools -> {
                    working += AgentChatMessage(
                        role = "assistant",
                        content = result.content,
                        toolCalls = result.calls
                    )
                    for (call in result.calls) {
                        val spec = AgentToolPolicy.toolByName(call.name)
                        if (spec == null) {
                            working += AgentChatMessage(
                                role = "tool",
                                content = AgentTurnMessagePolicy.unknownTool(call.name),
                                toolCallId = call.id,
                                toolName = call.name
                            )
                            continue
                        }
                        val args = AgentToolHost.parseArgs(call.argumentsJson)
                        when (AgentToolPolicy.approvalFor(call.name, args)) {
                            Approval.DENY -> {
                                working += AgentChatMessage(
                                    role = "tool",
                                    content = AgentTurnMessagePolicy.TOOL_DENIED,
                                    toolCallId = call.id,
                                    toolName = call.name
                                )
                            }
                            Approval.NEED_USER -> {
                                emit(
                                    AgentTurnEvent.NeedsApproval(
                                        PendingAgentApproval(call, AgentToolHost.preview(call.name, call.argumentsJson))
                                    )
                                )
                                return@flow
                            }
                            Approval.ALLOW -> {
                                if (call.name == AgentToolHostPolicy.REWRITE_TOOL_NAME) {
                                    val rewritten = AgentTextCompletion.rewriteViaModel(provider, args, complete)
                                    working += AgentChatMessage(
                                        role = "tool",
                                        content = rewritten,
                                        toolCallId = call.id,
                                        toolName = call.name
                                    )
                                    emit(AgentTurnEvent.ToolFinished(call.name, rewritten))
                                } else {
                                    emit(AgentTurnEvent.ToolStarted(call.name))
                                    val toolResult = executeTool(call.name, call.argumentsJson)
                                    working += AgentChatMessage(
                                        role = "tool",
                                        content = toolResult,
                                        toolCallId = call.id,
                                        toolName = call.name
                                    )
                                    emit(AgentTurnEvent.ToolFinished(call.name, toolResult))
                                }
                            }
                        }
                    }
                }
            }
        }
        emit(AgentTurnEvent.Failed(AgentTurnMessagePolicy.TOO_MANY_ROUNDS))
    }
}
