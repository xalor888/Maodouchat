package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.maodouchat.data.model.Message

internal class ChatDetailMessageTargetState {
    /** 当前回复引用的消息（null = 不在引用任何消息）。 */
    var replyTarget by mutableStateOf<Message?>(null)

    /** 搜索/导航跳转高亮的消息 id（高亮 1.8s 后清零，null = 无高亮）。 */
    var navigationHighlightMessageId by mutableStateOf<String?>(null)
}

/** 与原来的逐项 `remember { … }` 等价：持有类内部是普通的 `mutableStateOf`。 */
@Composable
internal fun rememberChatDetailMessageTargetState(): ChatDetailMessageTargetState =
    remember { ChatDetailMessageTargetState() }
