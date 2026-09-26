package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.maodouchat.ai.AiPrivacyPreferences

/**
 * G335（第十二批）：**AI 安全提示状态族**——本地安全提示开关 + 已关闭提示的消息 id 集合。
 *
 * 归族理由：这两个状态描述同一份本地偏好（`AiPrivacyPreferences`）的两个字段，
 * 且由同一个写操作联动——`dismissSafetyForMessage` 既改内存集合又写盘，原先在
 * Route 里是裸 `remember` + 一个散在 composable 里的本地函数。收进来后
 * 「内存-磁盘双写」的逻辑只此一份，ON_RESUME 的刷新也只剩一次调用。
 *
 * 范式：与 [ChatDetailAppearanceState] 一样是普通持有类（`remember`，不带 Saver）——
 * `localSafetyEnabled` 每次 ON_RESUME 都以偏好为准重读；`dismissedSafetyMessageIds`
 * 的磁盘偏好才是事实源，跨进程重建由 `remember` 初值重读即可，Saver 保留反而可能
 * 与「用户在别处改过设置」冲突。这里与第九/十批的 `listSaver` 写法刻意不同，
 * 是为了与原逐项 `remember { mutableStateOf(...) }` 的保存语义严格等价。
 */
internal class ChatDetailAiSafetyState(
    localSafetyEnabled: Boolean,
    dismissedSafetyMessageIds: Set<String>,
) {
    /** AI 本地安全提示是否开启（设置页可改，ON_RESUME 时由 [refreshFrom] 重读）。 */
    var localSafetyEnabled by mutableStateOf(localSafetyEnabled)

    /** 已被用户关闭安全提示的消息 id 集合（内存 + 磁盘双写）。 */
    var dismissedSafetyMessageIds by mutableStateOf(dismissedSafetyMessageIds)

    /**
     * 为某条消息关闭安全提示：空 id 或重复关闭直接返回；否则内存集合追加并写盘。
     * 原 Route 里这个本地函数直接捕获 `context`，收进来后由调用方传入
     * （与 [refreshFrom] 一致，语义逐字等价）。
     */
    fun dismissSafetyForMessage(context: Context, messageId: String) {
        if (messageId.isBlank() || messageId in dismissedSafetyMessageIds) return
        val next = dismissedSafetyMessageIds + messageId
        dismissedSafetyMessageIds = next
        AiPrivacyPreferences.setDismissedSafetyMessageIds(context, next)
    }

    /** ON_RESUME：设置页可能改了开关，重读本地偏好（原 Route 的逐行赋值逐字搬移）。 */
    fun refreshFrom(context: Context) {
        localSafetyEnabled = AiPrivacyPreferences.localSafetyEnabled(context)
    }
}

/** 与原来的逐项 `remember { mutableStateOf(...) }` 等价：初值一律现读偏好。 */
@Composable
internal fun rememberChatDetailAiSafetyState(context: Context): ChatDetailAiSafetyState =
    remember {
        ChatDetailAiSafetyState(
            localSafetyEnabled = AiPrivacyPreferences.localSafetyEnabled(context),
            dismissedSafetyMessageIds = AiPrivacyPreferences.dismissedSafetyMessageIds(context),
        )
    }
