package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import android.widget.Toast
import com.maodouchat.ui.screen.chatdetail.ChatDetailUiState
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.screen.chatdetail.forwardTargetName

/**
 * 转发目标选择弹窗（G75 从 `ChatDetailRoute.kt` 拆出，原 235 行）。
 *
 * 覆盖多选目标、搜索过滤、分页（每页 64）、合并转发开关、留言输入，
 * 以及 9.227 的**批量串行转发**（单协程内按顺序转发、最后补发留言）。
 *
 * **拆解约束**：本文件不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所有输入经参数显式传入。纯搬移，不改变任何判断。
 *
 * @param onCancel 清空待转发列表（原 `onCancel()`）
 * @param onSelectionCleared 转发成功后清空消息多选（原 `onSelectionCleared()`）
 */
@Composable
internal fun ChatDetailForwardPicker(
    messages: List<Message>,
    forwardTargets: List<Chat>,
    currentUserId: String,
    onCancel: () -> Unit,
    onForwardBatch: (messages: List<Message>, targetChatIds: List<String>, note: String) -> Unit,
    onSendTextToChat: (chatId: String, body: String) -> Unit,
    onSelectionCleared: () -> Unit,
    onLoadForwardTargets: () -> Unit,
    /** 源会话是否密聊：影响 B2 转发白名单判定（原 Route 局部 `secretActive`）。 */
    secretSource: Boolean = false,
) {
    val forwardMessages = messages
    val state = ChatDetailUiState(forwardTargets = forwardTargets, currentUserId = currentUserId)
    val context = LocalContext.current
    val secretWhitelistAddedTip = stringResource(R.string.secret_forward_whitelist_added)

    val forwardMeLabel = stringResource(R.string.chat_sender_me)
    var forwardQuery by remember(forwardMessages.map { it.id }.joinToString()) { mutableStateOf("") }
    var forwardNote by remember(forwardMessages.map { it.id }.joinToString()) { mutableStateOf("") }
    var forwardExpanded by remember(forwardMessages.map { it.id }.joinToString()) { mutableStateOf(false) }
    // 1.34：多选转发目标（勾选多个会话后一次转发）
    var selectedForwardChatIds by remember(forwardMessages.map { it.id }.joinToString()) { mutableStateOf<Set<String>>(emptySet()) }
    // 1.149：合并转发（多条文本消息合并为一条发送）
    val forwardMergeable = forwardMessages.size > 1 && forwardMessages.all {
        it.type == MessageType.TEXT || it.type == MessageType.MARKDOWN
    }
    var forwardMerged by remember(forwardMessages.map { it.id }.joinToString()) { mutableStateOf(false) }
    val filteredForwardTargets = remember(state.forwardTargets, forwardQuery) {
        val q = forwardQuery.trim()
        if (q.isEmpty()) state.forwardTargets
        else state.forwardTargets.filter { chat ->
            forwardTargetName(context, chat, state.currentUserId).contains(q, ignoreCase = true)
        }
    }
    val forwardPageSize = 64
    val visibleForwardTargets = if (forwardExpanded) {
        filteredForwardTargets
    } else {
        filteredForwardTargets.take(forwardPageSize)
    }
    AlertDialog(
        onDismissRequest = { onCancel() },
        title = { Text(if (forwardMessages.size == 1) stringResource(R.string.chat_forward_to) else stringResource(R.string.chat_forward_selected, forwardMessages.size)) },
        text = {
            if (state.forwardTargets.isEmpty()) {
                Text(stringResource(R.string.chat_no_forward_targets), color = MaterialTheme.colorScheme.secondary)
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                ) {
                    // 1.164：转发内容预览（最多显示 3 条，其余折叠为数量提示）
                    forwardMessages.take(3).forEach { fm ->
                        val previewText = when (fm.type) {
                            MessageType.IMAGE -> stringResource(R.string.message_preview_image)
                            MessageType.GIF -> stringResource(R.string.message_preview_gif)
                            MessageType.STICKER -> stringResource(R.string.message_preview_sticker)
                            MessageType.VOICE -> stringResource(R.string.message_preview_voice)
                            MessageType.VIDEO -> stringResource(R.string.message_preview_video)
                            MessageType.FILE -> stringResource(R.string.message_preview_file)
                            MessageType.LOCATION -> stringResource(R.string.message_preview_location)
                            else -> com.maodouchat.data.repository.ChatListPreviewPolicy.redactedIfWire(
                                fm.parsedContent(),
                                stringResource(R.string.chat_decrypt_failed)
                            ).replace('\n', ' ').take(40)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .background(LocalChatPalette.current.systemMessageBackground.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                previewText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (forwardMessages.size > 3) {
                        Text(
                            pluralStringResource(R.plurals.chat_forward_more_previews, forwardMessages.size - 3, forwardMessages.size - 3),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalChatPalette.current.textHint
                        )
                    }
                    OutlinedTextField(
                        value = forwardQuery,
                        onValueChange = {
                            forwardQuery = it
                            forwardExpanded = false
                        },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.chat_forward_search_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = forwardNote,
                        onValueChange = { forwardNote = it.take(500) },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.chat_forward_note_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    // 1.149：合并转发开关（仅多条文本消息时可用）
                    if (forwardMergeable) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.chat_forward_merge_title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                Text(stringResource(R.string.chat_forward_merge_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                            }
                            Switch(checked = forwardMerged, onCheckedChange = { forwardMerged = it })
                        }
                    }
                    if (filteredForwardTargets.isEmpty()) {
                        Text(
                            stringResource(R.string.chat_forward_search_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalChatPalette.current.textHint
                        )
                    } else {
                        // 1.159：最近会话快捷选择（顶部 6 个，点击勾选/取消）
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            filteredForwardTargets.take(6).forEach { recentChat ->
                                val recentSelected = selectedForwardChatIds.contains(recentChat.id)
                                FilterChip(
                                    selected = recentSelected,
                                    onClick = {
                                        if (recentSelected) {
                                            selectedForwardChatIds = selectedForwardChatIds - recentChat.id
                                        } else {
                                            selectedForwardChatIds = selectedForwardChatIds + recentChat.id
                                        }
                                    },
                                    label = { Text(forwardTargetName(context, recentChat, state.currentUserId), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                )
                            }
                        }
                        // B2 转发白名单（fwlz）：源为密聊且白名单开启时，非白名单目标需先加入白名单
                        val isSecretSource = secretSource
                        val fwlEnabled = com.maodouchat.util.SecretForwardWhitelistPrefs.isEnabled(context)
                        visibleForwardTargets.forEach { chat ->
                            val whitelisted = !fwlEnabled || com.maodouchat.util.SecretForwardWhitelistPrefs.isForwardAllowed(context, chat.id)
                            TextButton(
                                onClick = {
                                    // 1.34：点击勾选/取消目标会话（白名单目标可勾选）
                                    if (whitelisted) {
                                        selectedForwardChatIds =
                                            if (selectedForwardChatIds.contains(chat.id)) selectedForwardChatIds - chat.id
                                            else selectedForwardChatIds + chat.id
                                    }
                                },
                                enabled = !secretSource || whitelisted,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    if (selectedForwardChatIds.contains(chat.id)) {
                                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Text(forwardTargetName(context, chat, state.currentUserId), modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                                    if (secretSource && fwlEnabled && !whitelisted) {
                                        TextButton(onClick = {
                                            val whitelist = com.maodouchat.util.SecretForwardWhitelistPrefs.whitelist(context) + chat.id
                                            com.maodouchat.util.SecretForwardWhitelistPrefs.setWhitelist(context, whitelist)
                                            Toast.makeText(context, secretWhitelistAddedTip, Toast.LENGTH_SHORT).show()
                                        }) {
                                            Text(stringResource(R.string.secret_forward_whitelist_add), color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                        if (!forwardExpanded && filteredForwardTargets.size > forwardPageSize) {
                            TextButton(onClick = { forwardExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    stringResource(
                                        R.string.chat_forward_targets_more,
                                        filteredForwardTargets.size - forwardPageSize
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else if (forwardExpanded && filteredForwardTargets.size > forwardPageSize) {
                            Text(
                                stringResource(R.string.chat_forward_targets_showing_all, filteredForwardTargets.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalChatPalette.current.textHint
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onLoadForwardTargets() }) { Text(stringResource(R.string.common_refresh)) }
                // 1.34：确认转发到所选会话（可多选）
                TextButton(
                    enabled = selectedForwardChatIds.isNotEmpty(),
                    onClick = {
                        // 1.149：合并转发——多条文本合并为一条（带发送者标签）
                        val mergedText = if (forwardMerged && forwardMergeable) {
                            forwardMessages.joinToString("\n") { msg ->
                                val senderLabel = when {
                                    msg.senderId == state.currentUserId -> forwardMeLabel
                                    else -> state.chat?.participants?.firstOrNull { it.id == msg.senderId }?.displayName?.takeIf { it.isNotBlank() }
                                }
                                val content = msg.parsedContent().take(4_000)
                                (senderLabel?.let { "$it：$content" } ?: content)
                            }.take(4_000)
                        } else null
                        if (mergedText != null) {
                            selectedForwardChatIds.forEach { chatId ->
                                // 1.157：合并转发时附带留言并入合并文本首行（不单独发第二条）
                                val finalMerged = if (forwardNote.isNotBlank()) "$forwardNote\n$mergedText" else mergedText
                                onSendTextToChat(chatId, finalMerged)
                            }
                        } else {
                            // 9.227：批量串行转发——旧实现逐条并发触发 forwardMessage，
                            // 留言也抢在附件转发前发出；现改为单协程内按顺序串行转发+最后补发留言
                            onForwardBatch(forwardMessages, selectedForwardChatIds.toList(), forwardNote)
                        }
                        onCancel()
                        onSelectionCleared()
                    }
                ) {
                    Text(
                        if (selectedForwardChatIds.size > 1) {
                            stringResource(R.string.chat_forward_to_selected, selectedForwardChatIds.size)
                        } else {
                            stringResource(R.string.chat_forward_confirm)
                        },
                        color = if (selectedForwardChatIds.isNotEmpty()) Primary else TextSecondary
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = { onCancel() }) { Text(stringResource(R.string.common_cancel)) } }
    )}
