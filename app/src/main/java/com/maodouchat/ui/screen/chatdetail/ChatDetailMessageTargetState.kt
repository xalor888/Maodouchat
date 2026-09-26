package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.maodouchat.data.model.Message

/**
 * G337（第十六批）：**消息引用/导航族**——Route 里两个「指向某条消息的瞬态引用」。
 *
 * 归族理由：`replyTarget`（当前回复引用的消息）与 `navigationHighlightMessageId`
 * （搜索/导航跳转高亮的消息 id）都是「置值 → 被读 → 清零」的瞬态循环：引用/跳转时置值，
 * 发送后或高亮播完后清零；只在当前组合内有意义，不跨旋转/进程重建存活
 * （引用条与高亮本来就不恢复）。
 *
 * 范式：普通持有类（`remember`，不带 Saver）。`rememberSaveable` 的那一族
 * （43 个）仍在 Route 里——硬搬会静默丢失旋转恢复语义，要搬得先写 Saver。
 */
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
