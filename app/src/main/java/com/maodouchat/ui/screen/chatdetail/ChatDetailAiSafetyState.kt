package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.maodouchat.ai.AiPrivacyPreferences

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
