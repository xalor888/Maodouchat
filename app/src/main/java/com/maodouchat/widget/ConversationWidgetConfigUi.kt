package com.maodouchat.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.data.model.Chat
import com.maodouchat.data.repository.ChatRepository
import com.maodouchat.network.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * B5 小组件配置页 UI（纯 Compose，不触碰任何现有 UI 文件）。
 */
@Composable
internal fun ConversationWidgetConfigContent(
    initial: ConversationWidgetData.WidgetConfig,
    onSave: (List<String>, Boolean, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val tokenManager = remember { TokenManager.getInstance(context) }
    val ownerUserId = remember { tokenManager.getUserId().orEmpty() }

    var chats by remember { mutableStateOf<List<Chat>>(emptyList()) }
    var loadFailed by remember { mutableStateOf(false) }
    val maxConfigChats = ConversationWidgetContract.MAX_CONFIG_CHATS

    var selected by rememberSaveable { mutableStateOf(initial.chatIds.filter { it.isNotBlank() }.toSet()) }
    var showBadge by rememberSaveable { mutableStateOf(initial.showUnreadBadge) }
    var compact by rememberSaveable { mutableStateOf(initial.compact) }

    LaunchedEffect(Unit) {
        val app = context.applicationContext as? MaodouchatApp ?: return@LaunchedEffect
        try {
            chats = withContext(Dispatchers.IO) {
                ChatRepository(app.database.chatDao(), app.database.userDao()).getActiveChats().first()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            loadFailed = true
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = stringResource(R.string.widget_config_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.widget_config_subtitle, maxConfigChats),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))

            when {
                loadFailed -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = stringResource(R.string.widget_config_load_failed))
                    }
                }
                chats.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = stringResource(R.string.widget_config_loading))
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(chats, key = { it.id }) { chat ->
                            val chatId = chat.id
                            val isSelected = chatId in selected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = isSelected,
                                        onClick = {
                                            selected = if (isSelected) selected - chatId else selected + chatId
                                        }
                                    )
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + chatId else selected - chatId
                                    }
                                )
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    Text(
                                        text = chatTitle(chat, ownerUserId),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    if (chat.unreadCount > 0) {
                                        Text(
                                            text = stringResource(R.string.widget_config_unread, chat.unreadCount),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = stringResource(R.string.widget_config_badge_label))
                Switch(checked = showBadge, onCheckedChange = { showBadge = it })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = stringResource(R.string.widget_config_compact_label))
                Switch(checked = compact, onCheckedChange = { compact = it })
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { onSave(selected.toList(), showBadge, compact) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.widget_config_save))
            }
        }
    }
}

private fun chatTitle(chat: Chat, ownerUserId: String): String {
    if (chat.isGroup) return chat.groupName?.takeIf { it.isNotBlank() } ?: chat.id.take(12)
    return chat.participants
        .firstOrNull { it.id != ownerUserId }
        ?.let { it.displayName.ifBlank { it.name } }
        ?: chat.groupName?.takeIf { it.isNotBlank() }
        ?: chat.id.take(12)
}
