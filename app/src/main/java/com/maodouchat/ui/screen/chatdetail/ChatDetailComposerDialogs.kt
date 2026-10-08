package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 输入栏表情回应选择器。（计时/日程/消息动作对话框已拆出同包簇文件） */

@Composable
internal fun ReactionPickerRow(onPick: (String) -> Unit) {
    val reactions = remember {
        listOf(
            "\uD83D\uDC4D", // 👍
            "\uD83D\uDC4E", // 👎
            "\u2764\uFE0F", // ❤️
            "\uD83D\uDE02", // 😂
            "\uD83D\uDE0D", // 😍
            "\uD83D\uDE2E", // 😮
            "\uD83D\uDE22", // 😢
            "\uD83D\uDE21", // 😠
            "\uD83D\uDD25", // 🔥
            "\uD83C\uDF89", // 🎉
            "\uD83D\uDC4F", // 👏
            "\uD83D\uDE4F", // 🙏
            "\uD83D\uDC40", // 👀
            "\uD83E\uDD14", // 🤔
            "\uD83D\uDCAF", // 💯
            "\u2705",      // ✅
            "\uD83D\uDE80", // 🚀
            "\u2B50",      // ⭐
            "\uD83C\uDF1F", // 🌟
            "\uD83E\uDD73", // 🥳
            "\uD83E\uDD70", // 🥰
            "\uD83D\uDCAA", // 💪
            "\uD83E\uDD1D", // 🤝
            "\uD83D\uDE0A", // 😊
            "\uD83D\uDE4C", // 🙌
            "\uD83E\uDD29", // 🤩
            "\uD83E\uDD72", // 🥲
            "\uD83E\uDD23", // 🤣
            "\uD83D\uDC4C", // 👌
            "\uD83E\uDEF6"  // 🫶
        )
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp)
    ) {
        reactions.forEach { emoji ->
            TextButton(
                onClick = { onPick(emoji) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(emoji, fontSize = 20.sp, textAlign = TextAlign.Center)
            }
        }
    }
}
