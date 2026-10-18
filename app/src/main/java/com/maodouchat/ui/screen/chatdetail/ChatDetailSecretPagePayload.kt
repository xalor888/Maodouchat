package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import androidx.compose.runtime.Composable
import com.maodouchat.session.CurrentSession
import com.maodouchat.ui.component.rememberSecretPageWatermarkPayload
import com.maodouchat.watermark.DeviceHint

@Composable
internal fun rememberChatDetailSecretPagePayload(
    state: ChatDetailUiState,
    context: Context
): ByteArray? = rememberSecretPageWatermarkPayload(
    isSecretChat = state.isSecretChat == true,
    userId = CurrentSession.ownerUserId(),
    chatId = state.chat?.id,
    deviceHint = DeviceHint.androidId(context)
)
