package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.maodouchat.data.model.Message

internal class ChatDetailFullscreenMediaState {
    /** 当前全屏查看的图片消息；为 null 时图片对话框不展示。 */
    var fullScreenImage by mutableStateOf<Message?>(null)

    /** 当前全屏查看的视频消息；为 null 时视频对话框不展示。 */
    var fullScreenVideo by mutableStateOf<Message?>(null)
}

/** 与原来的逐项 `remember { … }` 等价：持有类内部是普通的 `mutableStateOf`。 */
@Composable
internal fun rememberChatDetailFullscreenMediaState(): ChatDetailFullscreenMediaState =
    remember { ChatDetailFullscreenMediaState() }
