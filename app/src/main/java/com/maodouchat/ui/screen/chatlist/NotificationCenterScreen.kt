package com.maodouchat.ui.screen.chatlist

import android.annotation.SuppressLint
import android.app.Application
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PhoneMissed
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ChecklistRtl
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.data.repository.NotificationCenterItem
import com.maodouchat.data.repository.NotificationCenterRepository
import com.maodouchat.ui.theme.Background
import com.maodouchat.ui.theme.Error
import com.maodouchat.ui.theme.MaodouDimens
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.MotionTokens
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.Surface
import com.maodouchat.ui.theme.TextHint
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.UnreadRed
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.maodouchat.ui.theme.LocalChatPalette

class NotificationCenterViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MaodouchatApp
    private val repo: NotificationCenterRepository = app.notificationCenter

    val items = repo.items.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = repo.snapshot()
    )

    fun markAllRead() {
        // Mark-all should also drop tray shadows so badge UX matches the center.
        val snapshot = repo.snapshot()
        repo.markAllRead()
        dismissTrayFor(snapshot)
    }

    /**
     * Merge can rewrite [NotificationCenterItem.id] to the incoming head while Compose
     * still holds the pre-merge id. Resolve by id first, then type + mergeKey.
     */
    private fun resolveLiveItem(id: String, hint: NotificationCenterItem? = null): NotificationCenterItem? {
        val snapshot = repo.snapshot()
        snapshot.firstOrNull { it.id == id }?.let { return it }
        val type = hint?.type
        val mergeKey = hint?.mergeKey
        if (!type.isNullOrBlank() && !mergeKey.isNullOrBlank()) {
            snapshot.firstOrNull { it.type == type && it.mergeKey == mergeKey }?.let { return it }
        }
        return null
    }

    fun markRead(id: String, hint: NotificationCenterItem? = null) {
        val item = resolveLiveItem(id, hint)
        val targetId = item?.id ?: id
        repo.markRead(targetId)
        if (item != null) dismissTrayFor(listOf(item))
    }

    fun markUnread(id: String, hint: NotificationCenterItem? = null) {
        val item = resolveLiveItem(id, hint)
        repo.markUnread(item?.id ?: id)
    }

    fun remove(id: String, hint: NotificationCenterItem? = null) {
        val item = resolveLiveItem(id, hint)
        val targetId = item?.id ?: id
        repo.remove(targetId)
        if (item != null) dismissTrayFor(listOf(item))
    }

    /** 1.170：清除某个会话的全部通知（含托盘通知）。 */
    fun removeChat(chatId: String) {
        if (chatId.isBlank()) return
        val affected = repo.snapshot().filter { item ->
            com.maodouchat.notification.NotificationCenterReadPolicy.belongsToChat(
                chatId = chatId,
                mergeKey = item.mergeKey,
                deeplink = item.deeplink,
                extraChatId = item.extra["chatId"]
            )
        }
        repo.removeChatItems(chatId)
        dismissTrayFor(affected)
    }

    fun clearAll() {
        val snapshot = repo.snapshot()
        repo.clearAll()
        dismissTrayFor(snapshot)
    }

    private fun dismissTrayFor(items: List<NotificationCenterItem>) {
        val ctx = getApplication<Application>()
        for (item in items) {
            try {
                when {
                    item.type == "MISSED_CALL" -> {
                        val callId = item.extra["callId"].orEmpty()
                        if (callId.isNotBlank()) {
                            com.maodouchat.util.AppNotifier.cancelMissedCall(ctx, callId)
                        }
                    }
                    item.type == "AI_TASK" -> {
                        val taskId = item.extra["taskId"].orEmpty()
                        val chatId = item.extra["chatId"].orEmpty()
                        when {
                            // 8.44：优先按 chat 整组清理（含 group-summary）——cancelAiTaskReminder
                            // 单任务不清理 summary，会造成托盘残留
                            chatId.isNotBlank() ->
                                com.maodouchat.util.AppNotifier.cancelAiTaskRemindersForChat(ctx, chatId)
                            taskId.isNotBlank() ->
                                com.maodouchat.util.AppNotifier.cancelAiTaskReminder(ctx, taskId)
                        }
                    }
                    item.type == "MESSAGE" || item.deeplink?.startsWith("maodouchat:chat:") == true -> {
                        val chatId = item.extra["chatId"]
                            ?: item.deeplink?.removePrefix("maodouchat:chat:")
                            ?: item.mergeKey.removePrefix("msg_")
                        if (chatId.isNotBlank()) {
                            com.maodouchat.util.AppNotifier.cancelMessage(ctx, chatId)
                        }
                    }
                    item.type == "POST_INTERACTION" ||
                        item.deeplink?.startsWith("maodouchat:post:") == true -> {
                        val postId = item.extra["postId"]
                            ?: item.deeplink?.removePrefix("maodouchat:post:")
                            ?: item.mergeKey.removePrefix("post_")
                        if (postId.isNotBlank()) {
                            com.maodouchat.util.AppNotifier.cancelPostInteraction(ctx, postId)
                        }
                    }
                    item.type == "FRIEND_REQUEST" || item.deeplink == "maodouchat:contacts" -> {
                        com.maodouchat.util.AppNotifier.cancelAllFriendRequests(ctx)
                    }
                    item.type == "GROUP_INVITE" || item.deeplink == "maodouchat:group_invites" -> {
                        com.maodouchat.util.AppNotifier.cancelAllGroupInvites(ctx)
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
fun NotificationCenterScreen(
    onBack: () -> Unit,
    onOpenItem: (NotificationCenterItem) -> Unit = {},
    viewModel: NotificationCenterViewModel = viewModel()
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }
    // 1.170：长按某条通知 → 清除该会话全部通知
    var clearChatId by remember { mutableStateOf<String?>(null) }
    // 1.268：长按操作菜单（复制 / 清除该会话通知）
    var notifyMenuFor by remember { mutableStateOf<Pair<NotificationCenterItem, String?>?>(null) }
    var filter by rememberSaveable { mutableStateOf(NotifFilter.ALL) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val motion = LocalMotionSettings.current

    val filteredItems = remember(items, filter, searchQuery) {
        val typeFiltered = items.filter { item -> filter.matches(item) }
        val query = searchQuery.trim()
        if (query.isBlank()) {
            typeFiltered
        } else {
            typeFiltered.filter { item ->
                item.title.contains(query, ignoreCase = true) ||
                    item.subtitle.orEmpty().contains(query, ignoreCase = true) ||
                    item.preview.orEmpty().contains(query, ignoreCase = true) ||
                    item.type.contains(query, ignoreCase = true) ||
                    item.extra.values.any { it.contains(query, ignoreCase = true) }
            }
        }
    }
    val groups = remember(filteredItems) { filteredItems.groupByDay() }
    val unreadCount = items.count { !it.read }
    val showSearch = items.size >= 5

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.notif_center_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                        if (unreadCount > 0) {
                            Text(
                                pluralStringResource(R.plurals.notif_center_unread_count, unreadCount, unreadCount),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.markAllRead() }, enabled = unreadCount > 0) {
                        Icon(Icons.Outlined.DoneAll, contentDescription = stringResource(R.string.notif_center_mark_all_read), tint = if (unreadCount > 0) Primary else TextHint)
                    }
                    IconButton(onClick = { showClearConfirm = true }, enabled = items.isNotEmpty()) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.notif_center_clear_all), tint = if (items.isNotEmpty()) Primary else TextHint)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            EmptyNotificationCenter(modifier = Modifier.fillMaxSize().padding(padding))
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                NotificationFilterStrip(
                    selected = filter,
                    onSelect = { filter = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaodouDimens.ScreenPadding, vertical = 8.dp)
                )
                if (showSearch) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it.take(160) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        placeholder = { Text(stringResource(R.string.notif_center_search_hint)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MaodouDimens.ScreenPadding)
                            .padding(bottom = 8.dp)
                    )
                }
                if (filteredItems.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(
                                if (searchQuery.isNotBlank()) R.string.notif_center_search_empty
                                else R.string.notif_center_filter_empty
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalChatPalette.current.textSecondary
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
                    ) {
                        groups.forEach { (dayBucket, dayItems) ->
                            item(key = "header_${dayBucket.name}", contentType = "day_header") {
                                DayHeader(dayBucket.displayLabel())
                            }
                            items(dayItems, key = NotificationCenterItem::id, contentType = { "notification" }) { item ->
                                val itemChatId = resolvedNotificationChatId(item)
                                NotificationRow(
                                    item = item,
                                    // 1.284：搜索关键词高亮（与 Explore/收藏/会话列表一致）
                                    highlightQuery = searchQuery,
                                    onClick = {
                                        viewModel.markRead(item.id, item)
                                        onOpenItem(item)
                                    },
                                    onDismiss = { viewModel.remove(item.id, item) },
                                    onLongClick = { notifyMenuFor = item to itemChatId },
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = if (motion.animationsEnabled) tween(motion.duration(MotionTokens.Standard)) else null,
                                        fadeOutSpec = if (motion.animationsEnabled) tween(motion.duration(MotionTokens.Fast)) else null,
                                        placementSpec = if (motion.animationsEnabled) spring(stiffness = 420f, dampingRatio = 0.88f) else null
                                    )
                                )
                                HorizontalDivider(color = LocalChatPalette.current.textHint.copy(alpha = 0.15f), modifier = Modifier.padding(start = 72.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.notif_center_clear_all)) },
            text = { Text(stringResource(R.string.notif_center_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearAll(); showClearConfirm = false }) {
                    Text(stringResource(R.string.notif_center_clear_confirm_ok), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // 1.170：清除某个会话的全部通知
    clearChatId?.let { chatId ->
        AlertDialog(
            onDismissRequest = { clearChatId = null },
            title = { Text(stringResource(R.string.notif_center_clear_chat_title)) },
            text = { Text(stringResource(R.string.notif_center_clear_chat_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeChat(chatId)
                    clearChatId = null
                }) {
                    Text(stringResource(R.string.notif_center_clear_chat_ok), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { clearChatId = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // 1.268：长按操作菜单（复制通知内容 / 清除该会话通知）
    notifyMenuFor?.let { (item, chatId) ->
        AlertDialog(
            onDismissRequest = { notifyMenuFor = null },
            title = { Text(stringResource(R.string.notif_center_actions)) },
            text = {
                Column {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val copyText = buildString {
                            append(item.title)
                            if (item.preview?.isNotBlank() == true) {
                                if (isNotEmpty()) append("\n")
                                append(item.preview)
                            }
                        }
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.notif_center_actions), copyText))
                        Toast.makeText(context, context.getString(R.string.chat_copied), Toast.LENGTH_SHORT).show()
                        notifyMenuFor = null
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.chat_copy), modifier = Modifier.fillMaxWidth())
                    }
                    if (!chatId.isNullOrBlank()) {
                        TextButton(onClick = {
                            notifyMenuFor = null
                            clearChatId = chatId
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.notif_center_clear_chat_title), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (item.read) {
                        TextButton(onClick = {
                            viewModel.markUnread(item.id, item)
                            notifyMenuFor = null
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.notif_center_mark_unread), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { notifyMenuFor = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

@Composable
private fun DayHeader(dayTitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.86f))
            .padding(horizontal = MaodouDimens.ScreenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(dayTitle, style = MaterialTheme.typography.labelMedium, color = LocalChatPalette.current.textSecondary, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun NotificationRow(
    item: NotificationCenterItem,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    // 1.284：搜索关键词高亮
    highlightQuery: String = ""
) {
    val (icon, accent) = iconForType(item)
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 8.48 修复 M4：未读行用淡主色背景区分（此前两分支相同 → 视觉高亮失效）
            .background(if (item.read) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primary.copy(alpha = 0.05f))
            .then(
                if (onLongClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                else Modifier.clickable(onClick = onClick)
            )
            .padding(horizontal = MaodouDimens.ScreenPadding, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.16f))
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // 1.284：搜索时标题高亮匹配关键词（无搜索时原样渲染）
                    if (highlightQuery.isBlank()) androidx.compose.ui.text.AnnotatedString(item.title)
                    else highlightedText(item.title, highlightQuery),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (item.read) FontWeight.Normal else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (item.count > 1) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("${item.count}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    relativeTime(item.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
            item.subtitle?.takeIf { it.isNotBlank() }?.let {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    if (highlightQuery.isBlank()) androidx.compose.ui.text.AnnotatedString(it)
                    else highlightedText(it, highlightQuery),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalChatPalette.current.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            item.preview?.takeIf { it.isNotBlank() }?.let {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    if (highlightQuery.isBlank()) androidx.compose.ui.text.AnnotatedString(it)
                    else highlightedText(it, highlightQuery),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textHint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (!item.read) {
            Box(modifier = Modifier.padding(top = 8.dp, start = 6.dp).size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
        Spacer(modifier = Modifier.width(0.dp))
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Outlined.DeleteSweep, contentDescription = null, tint = LocalChatPalette.current.textHint, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun EmptyNotificationCenter(modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.NotificationsOff, contentDescription = null, tint = LocalChatPalette.current.textHint, modifier = Modifier.size(72.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(stringResource(R.string.notif_center_empty_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(R.string.notif_center_empty_subtitle), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary)
        }
    }
}

/** extra.chatId → deeplink → mergeKey（msg_/ai_tasks_） */
internal fun resolvedNotificationChatId(item: NotificationCenterItem): String? {
    item.extra["chatId"]?.takeIf { it.isNotBlank() }?.let { return it }
    val deeplink = item.deeplink.orEmpty()
    when {
        deeplink.startsWith("maodouchat:chat:") ->
            deeplink.removePrefix("maodouchat:chat:").substringBefore(':')
                .takeIf { it.isNotBlank() }?.let { return it }
        deeplink.startsWith("maodouchat:ai_tasks:") ->
            deeplink.removePrefix("maodouchat:ai_tasks:").substringBefore(':')
                .takeIf { it.isNotBlank() }?.let { return it }
    }
    if (item.mergeKey.startsWith("msg_") && item.mergeKey.length > 4) {
        return item.mergeKey.removePrefix("msg_").takeIf { it.isNotBlank() }
    }
    if (item.mergeKey.startsWith("ai_tasks_") && item.mergeKey.length > 9) {
        return item.mergeKey.removePrefix("ai_tasks_").takeIf { it.isNotBlank() }
    }
    return null
}

private enum class NotifFilter {
    ALL,
    UNREAD,
    MESSAGE,
    MISSED_CALL,
    AI_TASK,
    POST_INTERACTION,
    FRIEND_REQUEST;

    fun matches(item: NotificationCenterItem): Boolean = when (this) {
        ALL -> true
        UNREAD -> !item.read
        MESSAGE -> item.type == NotificationCenterType.MESSAGE
        MISSED_CALL -> item.type == NotificationCenterType.MISSED_CALL
        AI_TASK -> item.type == NotificationCenterType.AI_TASK
        POST_INTERACTION -> item.type == NotificationCenterType.POST_INTERACTION
        FRIEND_REQUEST -> item.type == NotificationCenterType.FRIEND_REQUEST
    }
}

@Composable
private fun NotificationFilterStrip(
    selected: NotifFilter,
    onSelect: (NotifFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = listOf(
        NotifFilter.ALL to stringResource(R.string.notif_center_filter_all),
        NotifFilter.UNREAD to stringResource(R.string.notif_center_filter_unread),
        NotifFilter.MESSAGE to stringResource(R.string.notif_center_subtitle_message),
        NotifFilter.MISSED_CALL to stringResource(R.string.notif_center_filter_calls),
        NotifFilter.AI_TASK to stringResource(R.string.notif_center_filter_ai),
        NotifFilter.POST_INTERACTION to stringResource(R.string.notif_center_filter_social),
        NotifFilter.FRIEND_REQUEST to stringResource(R.string.notif_center_filter_friends)
    )
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { (filter, label) ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = { Text(label) }
            )
        }
    }
}

private fun iconForType(item: NotificationCenterItem): Pair<ImageVector, Color> {
    // 1.124：动态互动按 kind 区分图标（赞=爱心，评论/回复/评论赞=聊天气泡）
    if (item.type == NotificationCenterType.POST_INTERACTION) {
        val kind = item.extra["kind"].orEmpty()
        return if (kind == "comment" || kind == "comment_like") {
            Icons.Outlined.ChatBubbleOutline to Primary
        } else {
            Icons.Outlined.Favorite to Color(0xFFE91E63)
        }
    }
    return when (item.type) {
        NotificationCenterType.MISSED_CALL -> Icons.AutoMirrored.Outlined.PhoneMissed to UnreadRed
        NotificationCenterType.AI_TASK -> Icons.Outlined.ChecklistRtl to Primary
        NotificationCenterType.POST_INTERACTION -> Icons.Outlined.Favorite to Color(0xFFE91E63)
        NotificationCenterType.GROUP_INVITE -> Icons.Outlined.Public to Primary
        NotificationCenterType.SECURITY -> Icons.Outlined.VerifiedUser to Primary
        NotificationCenterType.MESSAGE -> Icons.Outlined.MarkChatUnread to Primary
        NotificationCenterType.FRIEND_REQUEST -> Icons.Outlined.PersonAdd to Primary
        else -> Icons.Outlined.Campaign to TextSecondary
    }
}

object NotificationCenterType {
    const val MESSAGE = "MESSAGE"
    const val MISSED_CALL = "MISSED_CALL"
    const val AI_TASK = "AI_TASK"
    const val POST_INTERACTION = "POST_INTERACTION"
    const val GROUP_INVITE = "GROUP_INVITE"
    const val SECURITY = "SECURITY"
    const val REPORT = "REPORT"
    const val MODERATION = "MODERATION"
    const val FRIEND_REQUEST = "FRIEND_REQUEST"
}

private fun List<NotificationCenterItem>.groupByDay(): List<Pair<StringBucket, List<NotificationCenterItem>>> {
    val now = System.currentTimeMillis()
    val dayMs = 24L * 3600L * 1000L
    return this
        .groupBy { item ->
            // 8.48 修复 M5：未来时间戳（时钟超前/服务端未来时间）强制归 TODAY——
            // 此前负 diffDays 落入 YESTERDAY/WEEK，未来 8 天以上也归 WEEK
            val diffDays = ((now - item.updatedAt) / dayMs).coerceAtLeast(0L)
            when {
                diffDays == 0L -> StringBucket.TODAY
                diffDays == 1L -> StringBucket.YESTERDAY
                diffDays <= 7L -> StringBucket.WEEK
                else -> StringBucket.EARLIER
            }
        }
        .map { (bucket, list) ->
            val ordered = list.sortedByDescending { it.updatedAt }
            bucket to ordered
        }
        .sortedBy { it.first.ordinal }
}

enum class StringBucket(val sortOrder: Int) {
    TODAY(0), YESTERDAY(1), WEEK(2), EARLIER(3);

    @Composable
    fun displayLabel(): String = when (this) {
        TODAY -> stringResource(R.string.notif_center_bucket_today)
        YESTERDAY -> stringResource(R.string.notif_center_bucket_yesterday)
        WEEK -> stringResource(R.string.notif_center_bucket_week)
        EARLIER -> stringResource(R.string.notif_center_bucket_earlier)
    }
}

@Composable
private fun relativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> stringResource(R.string.time_just_now)
        diff < 3600_000 -> stringResource(R.string.notif_center_minutes_ago, (diff / 60_000).toInt())
        diff < 86_400_000 -> stringResource(R.string.notif_center_hours_ago, (diff / 3600_000).toInt())
        else -> stringResource(R.string.notif_center_days_ago, (diff / 86_400_000).toInt())
    }
}

/** 1.284：通知中心搜索关键词高亮（复用 GlobalSearchTextHighlight，与 Explore/收藏/会话列表一致）。 */
@Composable
private fun highlightedText(text: String, query: String): androidx.compose.ui.text.AnnotatedString {
    val highlighted = remember(text, query) {
        com.maodouchat.ui.screen.chatlist.GlobalSearchTextHighlight.buildSnippet(text, query)
    }
    return androidx.compose.ui.text.buildAnnotatedString {
        if (highlighted.highlights.isEmpty()) {
            append(highlighted.text)
            return@buildAnnotatedString
        }
        var cursor = 0
        highlighted.highlights.forEach { span ->
            if (span.start > cursor) append(highlighted.text.substring(cursor, span.start))
            pushStyle(androidx.compose.ui.text.SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, background = Primary.copy(alpha = 0.12f)))
            append(highlighted.text.substring(span.start, span.end))
            pop()
            cursor = span.end
        }
        if (cursor < highlighted.text.length) append(highlighted.text.substring(cursor))
    }
}
