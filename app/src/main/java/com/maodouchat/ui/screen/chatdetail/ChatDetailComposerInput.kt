package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.TextHint
import com.maodouchat.ui.theme.UnreadRed

/**
 * 输入框本体（G95 从 `ChatDetailComponents.kt` 的 `ComposerPane` 拆出，原 46 行）。
 *
 * 内含三条容易在后续改动中被破坏的判定：
 * 1. **1.175 回车发送偏好三处联动**——`singleLine` / `imeAction` / `keyboardActions.onSend`
 *    必须同源于一个 `enterToSend`：开 → 单行 + IME Send + 回车即发；
 *    关 → 多行 + 回车换行。只改其中一处会出现「看着能回车发但实际换了行」这类割裂；
 * 2. **字数计数 80% 才出现、90% 变红**——不到 80% 不显示（避免常态噪音），
 *    超过 90% 用 `UnreadRed` 提示即将触顶；
 * 3. **容器与指示线全透明**——外层 `Box` 已画好圆角背景，TextField 自己的
 *    container/indicator 若不一并透明会画出双层边框。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * `Context` 只用 `LocalContext.current`（读回车发送偏好）。纯搬移，不改判断。
 *
 * @param value 当前输入文本
 * @param onValueChange 文本变化
 * @param isSending 是否正在发送（发送中禁用回车发送，防重复）
 */
@Composable
internal fun ChatDetailComposerInput(
    value: String,
    onValueChange: (String) -> Unit,
    isSending: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

Box(
    modifier = Modifier
        .clip(RoundedCornerShape(26.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .border(
            width = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            shape = RoundedCornerShape(26.dp)
        )
) {
    // 1.175：回车发送偏好（开 → 单行 + IME Send；关 → 多行回车换行）
    val enterToSend = com.maodouchat.util.ComposerPreferences.enterToSend(context)
    TextField(
        value = value, onValueChange = onValueChange,
        placeholder = { Text(stringResource(R.string.chat_message_placeholder), style = MaterialTheme.typography.bodyLarge, color = LocalChatPalette.current.textHint) },
        singleLine = enterToSend,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = if (enterToSend) androidx.compose.ui.text.input.ImeAction.Send else androidx.compose.ui.text.input.ImeAction.Default
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onSend = { if (enterToSend && !isSending) onSend() }
        ),
        trailingIcon = if (value.length >= com.maodouchat.ui.screen.chatdetail.ChatDetailViewModel.MAX_COMPOSER_TEXT_LENGTH * 8 / 10) {
            {
                Text(
                    text = "${value.length}/${com.maodouchat.ui.screen.chatdetail.ChatDetailViewModel.MAX_COMPOSER_TEXT_LENGTH}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (value.length >= com.maodouchat.ui.screen.chatdetail.ChatDetailViewModel.MAX_COMPOSER_TEXT_LENGTH * 9 / 10) UnreadRed else TextHint
                )
            }
        } else null,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
        ),
        textStyle = MaterialTheme.typography.bodyLarge,
        maxLines = 4,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 40.dp)
    )
}
}