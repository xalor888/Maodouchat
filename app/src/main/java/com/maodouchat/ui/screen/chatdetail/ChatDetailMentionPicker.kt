package com.maodouchat.ui.screen.chatdetail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette

/**
 * 「@提及」候选选择器（G89 从 `ChatDetailComponents.kt` 的 `ComposerPane` 拆出，原 61 行）。
 *
 * 内含三条容易在后续改动中被破坏的判定：
 * 1. **候选为空则不渲染**（`AnimatedVisibility(visible = candidates.isNotEmpty())`）——
 *    空列表时不该占任何高度，否则输入框会被顶上去；
 * 2. **`@所有人` 是候选之一**（`candidate.isEveryone`），文案用 `everyoneLabel`，
 *    且是否出现在候选里由 `MentionPolicy.filterCandidates(includeEveryone = …)` 决定，
 *    这里只负责渲染；
 * 3. **候选 ≥ 8 个时显示计数提示**——列表被限高 220dp，用户需要知道还有多少没显示。
 *
 * 点击候选时通过 `MentionPolicy.insertMention` 把 `@显示名 ` 插入输入框，
 * 光标固定 `value.length`（输入框就是当前草稿，没有跨组件光标状态）。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所需输入全部经参数显式传入。纯搬移，不改判断。
 *
 * @param candidates 候选列表（已由 `MentionPolicy.filterCandidates` 过滤）
 * @param everyoneLabel 「@所有人」的本地化文案
 * @param onInsertMention 插入成功后的新文本（路由回 `onValueChange`）
 * @param onShowCountHint 候选数 ≥ 8 时是否显示计数提示（默认开）
 */
@Composable
internal fun MentionCandidatePicker(
    candidates: List<MentionPolicy.Candidate>,
    everyoneLabel: String,
    modifier: Modifier = Modifier,
    showCountHint: Boolean = true,
    insertAction: (displayName: String) -> Unit,
) {
AnimatedVisibility(
    visible = candidates.isNotEmpty(),
    enter = expandVertically(tween(180)) + fadeIn(tween(180)),
    exit = shrinkVertically(tween(140)) + fadeOut(tween(140))
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
            .padding(vertical = 4.dp)
    ) {
        Text(
            stringResource(R.string.chat_mention_picker_title),
            style = MaterialTheme.typography.labelSmall,
            color = LocalChatPalette.current.textSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState())
        ) {
            candidates.forEach { candidate ->
                val label = if (candidate.isEveryone) everyoneLabel else candidate.displayName
                TextButton(
                    onClick = {
                        val insertLabel = if (candidate.isEveryone) everyoneLabel else candidate.displayName
                        insertAction(insertLabel)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (candidate.isEveryone) "@$everyoneLabel" else "@$label",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (showCountHint && candidates.size >= 8) {
            Text(
                stringResource(R.string.chat_mention_picker_count, candidates.size),
                style = MaterialTheme.typography.labelSmall,
                color = LocalChatPalette.current.textHint,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }
    }
}}
