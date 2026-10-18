package com.maodouchat.ui.screen.chatdetail

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.model.Message

// 长按弹层的 AI 场景入口：翻译/转写/图片与文件分析（密聊不展示）。
@Composable
internal fun ChatDetailMessageAiActions(
    message: Message,
    state: ChatDetailUiState,
    viewModel: ChatDetailViewModel,
    context: android.content.Context,
    voiceTranscript: String?,
    chatAiSurfacesVisible: Boolean,
    onMessageToTranslate: (Message?) -> Unit,
    onMessageToAnalyzeImage: (Message?) -> Unit,
    onMessageToAnalyzeFile: (Message?) -> Unit,
    onMessageToActions: (Message?) -> Unit,
) {
    // AI 场景入口（与输入栏主入口分区视觉统一）；密聊会话不提供（防解密明文送 AI）
    val contextAiActions = com.maodouchat.ai.AiEntryPolicy.contextActionsFor(
        messageType = message.type.name,
        hasTranscript = !voiceTranscript.isNullOrBlank()
    )
    if (
        contextAiActions.isNotEmpty() &&
        state.isSecretChat != true &&
        chatAiSurfacesVisible
    ) {
        Text(
            stringResource(R.string.chat_ai_section_context),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        )
        contextAiActions.forEach { action ->
            when (action) {
                com.maodouchat.ai.MessageAiAction.TRANSLATE -> {
                    val busy = message.id in state.translatingMessageIds
                    TextButton(
                        enabled = com.maodouchat.ai.AiEntryPolicy.canRunContextAction(context, state.aiEnabled, busy),
                        onClick = {
                            if (!state.aiEnabled) {
                                Toast.makeText(context, context.getString(R.string.chat_ai_disabled_short), Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            onMessageToTranslate(message)
                            onMessageToActions(null)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(if (busy) R.string.chat_translating else R.string.chat_translate),
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                com.maodouchat.ai.MessageAiAction.TRANSCRIBE -> {
                    val busy = message.id in state.transcribingVoiceMessageIds
                    TextButton(
                        enabled = com.maodouchat.ai.AiEntryPolicy.canRunContextAction(context, state.aiEnabled, busy),
                        onClick = {
                            if (!state.aiEnabled) {
                                Toast.makeText(context, context.getString(R.string.chat_ai_disabled_short), Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            viewModel.requestVoiceTranscription(message.id)
                            onMessageToActions(null)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(if (busy) R.string.chat_transcribing else R.string.chat_transcribe),
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                com.maodouchat.ai.MessageAiAction.ANALYZE_IMAGE -> {
                    val busy = message.id in state.analyzingImageMessageIds
                    TextButton(
                        enabled = com.maodouchat.ai.AiEntryPolicy.canRunContextAction(context, state.aiEnabled, busy),
                        onClick = {
                            if (!state.aiEnabled) {
                                Toast.makeText(context, context.getString(R.string.chat_ai_disabled_short), Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            onMessageToAnalyzeImage(message)
                            onMessageToActions(null)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(if (busy) R.string.chat_ai_image_analyzing else R.string.chat_ai_image_action),
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                com.maodouchat.ai.MessageAiAction.ANALYZE_FILE -> {
                    val busy = message.id in state.analyzingFileMessageIds
                    TextButton(
                        enabled = com.maodouchat.ai.AiEntryPolicy.canRunContextAction(context, state.aiEnabled, busy),
                        onClick = {
                            if (!state.aiEnabled) {
                                Toast.makeText(context, context.getString(R.string.chat_ai_disabled_short), Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            onMessageToAnalyzeFile(message)
                            onMessageToActions(null)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(if (busy) R.string.chat_ai_file_analyzing else R.string.chat_ai_file_action),
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
