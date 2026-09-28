package com.maodouchat.ui.screen.chatdetail

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.perf.CoalescedScroller
import com.maodouchat.ui.component.EmptyState
import com.maodouchat.ui.component.EmptyStateType
import com.maodouchat.ui.theme.MotionSettings
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.UnreadRed

/**
 * G350：`ChatTimelinePane` 的内容（消息列表 + 回底 FAB + 空态，135 行）从
 * `ChatDetailRoute.kt` 抽出（纯搬移不改判断）。
 *
 * 是 [BoxScope] 扩展——FAB 的 `align(BottomEnd)` 与空态的 `align(Center)` 依赖
 * `ChatTimelinePane` 的 Box 作用域（同 `LazyItemScope.ChatDetailTimelineItem` 的先例）。
 * 依赖全经参数注入；组合期内不新增状态所有权。
 */
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串在回调内读取，非组合作用域；lint 无法区分（同 ChatDetailRoute）
@Composable
internal fun BoxScope.ChatDetailTimelineSection(
    state: ChatDetailUiState,
    viewModel: ChatDetailViewModel,
    listState: LazyListState,
    reversedChatItems: List<ChatItem>,
    motion: MotionSettings,
    drafts: ChatDetailDraftState,
    messageSelectionMode: Boolean,
    particles: ChatDetailParticleState,
    searchResults: List<Message>,
    search: ChatDetailSearchState,
    aiSafety: ChatDetailAiSafetyState,
    targets: ChatDetailMessageTargetState,
    messagesById: Map<String, Message>,
    resolveSenderName: (Message, Boolean) -> String?,
    bubbleBounds: ChatDetailBubbleBoundsState,
    media: ChatDetailFullscreenMediaState,
    messageActions: ChatDetailMessageActionState,
    scroll: ChatDetailScrollState,
    chatListScroller: CoalescedScroller,
    chatClipboardTranscriptLabel: String,
    onOpenProfile: ((String) -> Unit)?,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        // 9.264：TG 式分组密度基线——组内紧凑 3dp，跨组由消息项额外 padding 补到 8dp
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
    itemsIndexed(
        reversedChatItems,
        key = { _, item -> item.listKey },
        contentType = { _, item -> when (item) {
            is ChatItem.DateSeparator -> "date_separator"
            is ChatItem.Msg -> "message_${item.message.type.name}"
            is ChatItem.UnreadSeparator -> "unread_separator"
        } }
    ) { index, item ->
        // G85：单条 item 渲染（231 行）抽到 ChatDetailTimelineItems.kt。
        // 必须是 LazyItemScope 扩展——`Modifier.animateItem` 只在 itemsIndexed 内有效，
        // 降级成普通 Composable 会让重排时的位移动画静默消失。
        ChatDetailTimelineItem(
            index = index,
            item = item,
            state = state,
            listState = listState,
            motion = motion,
            allItems = reversedChatItems,
            selectedMessageIds = drafts.selectedMessageIds,
            messageSelectionMode = messageSelectionMode,
            animatingMessageId = particles.animatingMessageId,
            searchResults = searchResults,
            searchIndex = search.searchIndex,
            showSearchBar = search.showSearchBar,
            localSafetyEnabled = aiSafety.localSafetyEnabled,
            navigationHighlightMessageId = targets.navigationHighlightMessageId,
            dismissedSafetyMessageIds = aiSafety.dismissedSafetyMessageIds,
            messagesById = messagesById,
            resolveSenderName = { msg, isOwn -> resolveSenderName(msg, isOwn) },
            viewModel = viewModel,
            onBubblePlaced = { id, bounds -> bubbleBounds[id] = bounds },
            onBubbleRemoved = { id -> bubbleBounds.remove(id) },
            onShowFullscreenImage = { media.fullScreenImage = it },
            onShowFullscreenVideo = { media.fullScreenVideo = it },
            onCopyTranscript = { transcript ->
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(chatClipboardTranscriptLabel, transcript))
                Toast.makeText(context, context.getString(R.string.chat_transcript_copied), Toast.LENGTH_SHORT).show()
            },
            onReplyTo = { msg -> targets.replyTarget = msg },
            onDismissSafetyForMessage = { id -> aiSafety.dismissSafetyForMessage(context, id) },
            onToggleSelection = { drafts.selectedMessageIds = it },
            onRetryMessage = { msg -> messageActions.messageToRetry = msg },
            onMessageActions = { msg -> messageActions.messageToActions = msg },
            onOpenProfile = { userId -> onOpenProfile?.invoke(userId) },
            onShowReadReceipts = { msg -> messageActions.messageForReadReceipts = msg },
        )
    }
    if (state.isLoadingOlderMessages) {
        item(key = "older_messages_loading", contentType = "loading") {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
    }
    item(key = "message_list_footer", contentType = "footer") { Spacer(modifier = Modifier.height(8.dp)) }
    }

    androidx.compose.animation.AnimatedVisibility(
        visible = !scroll.isNearBottom,
        enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.86f),
        exit = fadeOut(tween(140)) + scaleOut(tween(160), targetScale = 0.9f),
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 14.dp, bottom = 12.dp)
    ) {
        Box {
            FloatingActionButton(
                onClick = {
                    scroll.pendingNewMessageCount = 0
                    chatListScroller.scrollToItem(listState, 0)
                },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = Primary,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp, pressedElevation = 6.dp),
                modifier = Modifier.size(46.dp)
            ) {
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.chat_scroll_to_latest),
                    modifier = Modifier.size(26.dp)
                )
            }
            if (scroll.pendingNewMessageCount > 0) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                        .size(20.dp)
                        .background(UnreadRed, CircleShape)
                ) {
                    Text(
                        text = if (scroll.pendingNewMessageCount > 99) "99+" else scroll.pendingNewMessageCount.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onError
                    )
            }
        }
    }
    }

    // 8.52 UX：初次加载失败（无本地缓存）→ 错误空态 + 重试，区别于真实空会话
    if (!state.isLoading && state.messages.isEmpty() && state.initialLoadError != null) {
        EmptyState(
            title = stringResource(R.string.chat_load_failed_title),
            subtitle = state.initialLoadError,
            type = EmptyStateType.NETWORK_ERROR,
            actionText = stringResource(R.string.chat_load_failed_retry),
            onAction = { viewModel.reloadChat() },
            modifier = Modifier.align(Alignment.Center).fillMaxWidth()
        )
    } else if (!state.isLoading && state.messages.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.chat_detail_empty_title),
            subtitle = stringResource(R.string.chat_detail_empty_subtitle),
            type = EmptyStateType.CHAT_LIST,
            modifier = Modifier.align(Alignment.Center).fillMaxWidth()
        )
    }
}
