package com.maodouchat.ui.screen.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.maodouchat.ai.agent.MaodouAgentService

/**
 * Nekogram / Telegram 风格的 AI 智能助手全功能界面。
 * 包含端侧大模型对话、流式回复、工具调用确认、引导页快捷推荐卡片以及高响应度微动效。
 */
@Composable
fun MaodouAgentScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { MaodouAgentService.init(context) }

    val messages = MaodouAgentService.messages
    val pending = MaodouAgentService.pendingApproval.value
    val error = MaodouAgentService.errorMessage.value
    val running = MaodouAgentService.ballState.value == MaodouAgentService.BallState.RUNNING
    val streamingText = MaodouAgentService.streamingText.value
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, streamingText) {
        val totalCount = messages.size + (if (streamingText.isNotBlank()) 1 else 0)
        if (totalCount > 0) {
            listState.animateScrollToItem(totalCount - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        AgentTopBar(running = running, onBack = onBack)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val visibleMessages = messages.filter { it.role != "system" }

            if (visibleMessages.isEmpty() && streamingText.isBlank()) {
                AgentWelcomePane { query -> MaodouAgentService.send(context, query) }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visibleMessages, key = { it.hashCode() to it.content }) { message ->
                        if (message.role == "tool") {
                            AgentToolStatusCard(message)
                        } else {
                            AgentMessageRow(message)
                        }
                    }

                    if (streamingText.isNotBlank()) {
                        item { AgentStreamingBubble(streamingText) }
                    } else if (running) {
                        item { AgentThinkingIndicator() }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = error != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = error.orEmpty(),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }

        if (pending != null) {
            AgentApprovalCard(
                pending = pending,
                onApprove = { MaodouAgentService.approvePending(context) },
                onReject = { MaodouAgentService.rejectPending(context) }
            )
        }

        AgentInputBar(
            running = running,
            hasPending = pending != null,
            onSend = { MaodouAgentService.send(context, it) }
        )
    }
}
