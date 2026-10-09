package com.maodouchat.ai.agent

import com.maodouchat.ai.AiPromptSafetyPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

// 轮次执行器：消息装配走 AgentTurnMessages，文本直连走 AgentTextCompletion。
class AgentSessionEngine(
    private val complete: suspend (
        LocalAiProvider,
        List<AgentChatMessage>,
        List<Map<String, Any?>>?,
        ((String) -> Unit)?
    ) -> OpenAiCompatClient.Completion = { provider, messages, tools, onDelta ->
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
                is OpenAiCompatClient.Completion.Error -> {
                    emit(AgentTurnEvent.Failed(result.message))
                    return@flow
                }
                is OpenAiCompatClient.Completion.Text -> {
                    val text = AiPromptSafetyPolicy.annotateIfPrivilegedHallucination(
                        result.content.trim(),
                        "助手不能执行未审批的特权动作，请以本机实际结果为准。"
                    )
                    emit(AgentTurnEvent.AssistantFinal(text))
                    return@flow
                }
                is OpenAiCompatClient.Completion.Tools -> {
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
                                content = "Error: unknown tool ${call.name}",
                                toolCallId = call.id,
                                toolName = call.name
                            )
                            continue
                        }
                        val args = AgentToolHost.parseArgs(call.argumentsJson)
                        when (AgentToolPolicy.approvalFor(call.name, args)) {
                            AgentToolPolicy.Approval.DENY -> {
                                working += AgentChatMessage(
                                    role = "tool",
                                    content = "Error: tool denied",
                                    toolCallId = call.id,
                                    toolName = call.name
                                )
                            }
                            AgentToolPolicy.Approval.NEED_USER -> {
                                emit(
                                    AgentTurnEvent.NeedsApproval(
                                        PendingAgentApproval(call, AgentToolHost.preview(call.name, call.argumentsJson))
                                    )
                                )
                                return@flow
                            }
                            AgentToolPolicy.Approval.ALLOW -> {
                                if (call.name == "rewrite_text") {
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
        emit(AgentTurnEvent.Failed("工具轮次过多，已停止"))
    }
}
