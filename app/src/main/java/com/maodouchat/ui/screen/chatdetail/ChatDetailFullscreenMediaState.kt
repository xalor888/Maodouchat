package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.maodouchat.data.model.Message

/**
 * G335（第十四批）：**全屏媒体查看状态族**——当前正以全屏方式查看的那条图片/视频消息。
 *
 * 归族理由：`fullScreenImage` 与 `fullScreenVideo` 总是成对出现，是「全屏媒体对话框
 * 当前展示什么」的两个侧面：打开入口（时间线的 `onShowFullscreenImage` /
 * `onShowFullscreenVideo`）置值，弹窗的 `onDismiss` 清零，Route 侧只有接线。
 * 语义与原逐项 `remember` 逐字一致（互斥与否按原逻辑，原逻辑下两侧各自独立置值/清零）。
 *
 * 范式：普通持有类（`remember`，不带 Saver）。全屏预览是纯瞬态视图状态：
 * 旋转/进程重建后对话框自然消失，没有「恢复上次在看哪条」的需求，故不进 saveable。
 * （`saveable` 族共 43 个仍在 Route 里——硬搬会静默丢失旋转恢复语义，要搬得先写 Saver，
 * 详见 checklist 第十一轮小节。）
 */
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
