package com.maodouchat.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import com.maodouchat.ui.theme.LocalSentBubbleContent
import com.maodouchat.ui.theme.LocalSentBubbleContentSecondary
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 交互卡片（G129 从 `MediaMessageBubbles.kt` 拆出，原 185 行）。
 *
 * `InteractivePollCard`（投票卡片：题目、选项、票数、截止时间、已投态）与
 * `InlineKeyboardGrid`（行内键盘按钮网格）。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`。
 * 纯搬移，不改判断。
 */

// ─── InteractivePollCard ───
@Composable
internal fun InteractivePollCard(
    pollJson: org.json.JSONObject,
    isOwnMessage: Boolean,
    onVote: ((String, Int) -> Unit)?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pollId = pollJson.optString("id")
    val question = pollJson.optString("q")
    val opts = pollJson.optJSONArray("options")
    val optionList = remember(pollJson.toString()) {
        buildList {
            if (opts != null) {
                for (i in 0 until opts.length()) add(opts.optString(i))
            }
        }
    }
    var counts by remember(pollId) { mutableStateOf(List(optionList.size) { 0 }) }
    var myVotes by remember(pollId) { mutableStateOf(emptySet<Int>()) }
    var totalVoters by remember(pollId) { mutableIntStateOf(0) }
    var closed by remember(pollId) { mutableStateOf(false) }
    var loading by remember(pollId) { mutableStateOf(false) }
    var voting by remember(pollId) { mutableStateOf(false) }

    fun applyServerJson(raw: String) {
        val o = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return
        val cArr = o.optJSONArray("counts")
        if (cArr != null) {
            counts = List(optionList.size) { idx -> if (idx < cArr.length()) cArr.optInt(idx) else 0 }
        }
        val my = o.optJSONArray("myVotes")
        myVotes = buildSet {
            if (my != null) for (i in 0 until my.length()) add(my.optInt(i))
        }
        totalVoters = o.optInt("totalVoters", totalVoters)
        closed = o.optBoolean("closed", closed)
    }

    LaunchedEffect(pollId) {
        if (pollId.isBlank()) return@LaunchedEffect
        loading = true
        val token = TokenManager.getInstance(context).getToken().orEmpty()
        if (token.isNotBlank()) {
            val result = withContext(Dispatchers.IO) { ApiService.getGroupPoll(token, pollId) }
            result.onSuccess { applyServerJson(it) }
        }
        loading = false
    }

    val titleColor = if (isOwnMessage) LocalSentBubbleContent.current else OnSurface
    val subColor = if (isOwnMessage) LocalSentBubbleContentSecondary.current else TextHint
    val chipBg = if (isOwnMessage) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.06f)
    val selectedBg = if (isOwnMessage) Color.White.copy(alpha = 0.28f) else Primary.copy(alpha = 0.16f)
    val maxCount = (counts.maxOrNull() ?: 0).coerceAtLeast(1)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "📊 $question",
            style = MaterialTheme.typography.titleSmall,
            color = titleColor,
            fontWeight = FontWeight.SemiBold
        )
        optionList.forEachIndexed { index, label ->
            val count = counts.getOrElse(index) { 0 }
            val selected = index in myVotes
            val fraction = count.toFloat() / maxCount.toFloat()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) selectedBg else chipBg)
                    .then(
                        if (onVote != null && pollId.isNotBlank() && !closed && !voting) {
                            Modifier.clickable {
                                if (voting || closed) return@clickable
                                voting = true
                                // Single path: ViewModel.votePoll -> API. Card only refreshes tallies.
                                onVote(pollId, index)
                                scope.launch {
                                    kotlinx.coroutines.delay(350)
                                    val token = TokenManager.getInstance(context).getToken().orEmpty()
                                    if (token.isNotBlank()) {
                                        val result = withContext(Dispatchers.IO) {
                                            ApiService.getGroupPoll(token, pollId)
                                        }
                                        result.onSuccess { applyServerJson(it) }
                                    }
                                    voting = false
                                }
                            }
                        } else Modifier
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${index + 1}. $label",
                        style = MaterialTheme.typography.bodyMedium,
                        color = titleColor
                    )
                    // tally bar
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.Black.copy(alpha = 0.08f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                                .height(4.dp)
                                .background(if (isOwnMessage) Color.White.copy(alpha = 0.7f) else Primary)
                        )
                    }
                }
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = subColor,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        val status = buildString {
            if (closed) append("closed · ")
            append(stringResource(R.string.group_play_vote_hint))
            if (totalVoters > 0) append(" · ").append(totalVoters)
            if (loading) append(" · ...")
        }
        Text(text = status, style = MaterialTheme.typography.labelSmall, color = subColor)
    }
}

// ─── InlineKeyboardGrid ───
@Composable
internal fun InlineKeyboardGrid(
    rows: List<List<InlineKeyboardButtonPresentation>>,
    isOwnMessage: Boolean,
    messageId: String,
    onClick: ((String, String) -> Unit)?
) {
    if (rows.isEmpty() || onClick == null) return
    Column(
        modifier = Modifier
            .padding(top = 6.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                row.forEach { btn ->
                    val bg = if (isOwnMessage) Color.White.copy(alpha = 0.18f) else Primary.copy(alpha = 0.10f)
                    val fg = if (isOwnMessage) Color.White else Primary
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(bg)
                            .clickable {
                                val data = btn.callbackData.ifBlank { btn.text }
                                onClick(messageId, data)
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = btn.text,
                            style = MaterialTheme.typography.labelMedium,
                            color = fg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
