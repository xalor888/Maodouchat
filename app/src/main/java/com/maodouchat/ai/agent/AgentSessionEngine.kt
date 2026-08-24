package com.maodouchat.ai.agent

import com.maodouchat.ai.AiPromptSafetyPolicy
import com.maodouchat.ai.AiWritingStylePolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
        val now = Instant.now().atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val system = AgentChatMessage(
            role = "system",
            content = AgentToolPolicy.systemPrompt(now, styleHint)
        )
        val working = mutableListOf<AgentChatMessage>()
        working += system
        working += history.takeLast(provider.clampedHistoryLimit())
            .filter { it.role != "system" }
        working += AgentChatMessage(role = "user", content = userText.take(4_000))
        if (approvedCall != null) {
            val result = executeTool(approvedCall.name, approvedCall.argumentsJson)
            working += AgentChatMessage(
                role = "assistant",
                content = "",
                toolCalls = listOf(approvedCall)
            )
            working += AgentChatMessage(
                role = "tool",
                content = result,
                toolCallId = approvedCall.id,
                toolName = approvedCall.name
            )
            emit(AgentTurnEvent.ToolFinished(approvedCall.name, result))
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
                                    val rewritten = rewriteViaModel(provider, args)
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

    suspend fun completeText(
        provider: LocalAiProvider,
        instruction: String,
        userContent: String,
        onDelta: ((String) -> Unit)? = null
    ): Result<String> {
        val messages = listOf(
            AgentChatMessage(role = "system", content = instruction),
            AgentChatMessage(role = "user", content = userContent.take(8_000))
        )
        return when (val result = complete(provider, messages, null, onDelta)) {
            is OpenAiCompatClient.Completion.Text -> Result.success(result.content.trim())
            is OpenAiCompatClient.Completion.Tools -> Result.success(result.content.trim())
            is OpenAiCompatClient.Completion.Error -> Result.failure(IllegalStateException(result.message))
        }
    }

    private suspend fun rewriteViaModel(
        provider: LocalAiProvider,
        args: Map<String, String>
    ): String {
        val text = args["text"].orEmpty()
        if (text.isBlank()) return "Error: text required"
        val mode = args["mode"] ?: "polish"
        return completeText(
            provider,
            AgentToolPolicy.rewriteInstruction(mode, args["targetLanguage"]),
            text
        ).getOrElse { it.message ?: "rewrite failed" }
    }

    companion object {
        fun styleHintFrom(snapshot: AiWritingStylePolicy.Snapshot): String? =
            AiWritingStylePolicy.rewriteStyleHint(snapshot)
    }
}
