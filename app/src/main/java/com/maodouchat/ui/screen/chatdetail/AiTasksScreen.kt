package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.R
import com.maodouchat.data.local.entity.AiTaskEntity
import com.maodouchat.ui.component.EmptyState
import com.maodouchat.ui.component.EmptyStateType
import com.maodouchat.ui.component.rememberSecretPageWatermarkPayload
import com.maodouchat.ui.component.secretPageBlindWatermark
import com.maodouchat.ui.theme.LocalChatPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiTasksScreen(
    onBack: () -> Unit,
    viewModel: AiTasksViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var taskToDelete by remember { mutableStateOf<AiTaskEntity?>(null) }
    var taskFilter by rememberSaveable { mutableStateOf(AiTaskFilter.ALL) }
    var taskSearch by rememberSaveable { mutableStateOf("") }

    // Opening tasks for this chat should clear matching tray reminders (parity with open-chat message cancel).
    LaunchedEffect(Unit) {
        val chatId = viewModel.chatId
        if (chatId.isNotBlank()) {
            com.maodouchat.notification.ReminderNotificationService.cancelAiTaskRemindersForChat(context.applicationContext, chatId)
            // 8.48 修复：连同 WorkManager 提醒作业一并取消——此前只清托盘，到点仍会弹新通知
            // U02 延伸：取 id + 逐个取消收进非 ui 的 AiTaskReminderCleanup。
            com.maodouchat.ai.AiTaskReminderCleanup.cancelForChatAsync(chatId)
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val secretPagePayload = rememberSecretPageWatermarkPayload(
        isSecretChat = state.isSecretChat,
        userId = com.maodouchat.session.CurrentSession.ownerUserId(),
        chatId = viewModel.chatId,
        deviceHint = com.maodouchat.watermark.DeviceHint.androidId(context)
    )

    if (state.isChatLocked == true) {
        Box(modifier = Modifier.fillMaxSize()) {
            ChatLockGate(
                chatName = state.chatName.ifBlank { stringResource(R.string.chat_this_chat) },
                onUnlock = { pin, onResult -> viewModel.unlockWithPin(pin, onResult) },
                onForgotPin = onBack
            )
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    } else {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .secretPageBlindWatermark(secretPagePayload)
    ) {
    Scaffold(
        containerColor = LocalChatPalette.current.chatBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_tasks_title), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    // 1.304：一键清空已完成任务
                    if (!state.isLoading && state.tasks.any { it.isCompleted }) {
                        IconButton(onClick = { viewModel.clearCompleted() }) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = stringResource(R.string.ai_tasks_clear_completed),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.shadow(1.dp)
            )
        }
    ) { padding ->
        when {
            state.isLoading || state.isChatLocked == null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            // 8.52 UX：加载失败且无任务 → 错误空态 + 重试（此前落入误导性的「暂无 AI 任务」）
            state.error != null && state.tasks.isEmpty() -> EmptyState(
                type = EmptyStateType.NETWORK_ERROR,
                title = stringResource(R.string.ai_tasks_load_failed),
                subtitle = state.error,
                actionText = stringResource(R.string.chat_load_failed_retry),
                onAction = viewModel::reloadTasks,
                modifier = Modifier.fillMaxSize().padding(padding)
            )

            state.tasks.isEmpty() -> AiTasksEmptyState(
                modifier = Modifier.fillMaxSize().padding(padding)
            )

            else -> {
                val pendingCount = state.tasks.count { !it.isCompleted }
                val completedCount = state.tasks.size - pendingCount
                val showTaskSearch = state.tasks.size >= 4
                val visibleTasks = remember(state.tasks, taskFilter, taskSearch) {
                    val statusFiltered = when (taskFilter) {
                        AiTaskFilter.ALL -> state.tasks
                        AiTaskFilter.PENDING -> state.tasks.filter { !it.isCompleted }
                        AiTaskFilter.COMPLETED -> state.tasks.filter { it.isCompleted }
                    }
                    val query = taskSearch.trim()
                    if (query.isBlank()) {
                        statusFiltered
                    } else {
                        statusFiltered.filter { task ->
                            task.title.contains(query, ignoreCase = true) ||
                                task.owner.orEmpty().contains(query, ignoreCase = true) ||
                                task.dueText.orEmpty().contains(query, ignoreCase = true) ||
                                task.sourceQuery.contains(query, ignoreCase = true)
                        }
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    item(key = "summary", contentType = "summary") {
                        AiTaskSummary(pendingCount = pendingCount, completedCount = completedCount)
                    }
                    item(key = "filter", contentType = "filter") {
                        AiTaskFilterStrip(
                            selected = taskFilter,
                            onSelect = { taskFilter = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    if (showTaskSearch) {
                        item(key = "task_search", contentType = "search") {
                            OutlinedTextField(
                                value = taskSearch,
                                onValueChange = { taskSearch = it.take(160) },
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                placeholder = { Text(stringResource(R.string.ai_tasks_search_hint)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 8.dp)
                            )
                        }
                    }
                    if (visibleTasks.isEmpty()) {
                        item(key = "filter_empty", contentType = "filter_empty") {
                            Text(
                                stringResource(
                                    if (taskSearch.isNotBlank()) R.string.ai_tasks_search_empty
                                    else R.string.ai_tasks_filter_empty
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = LocalChatPalette.current.textSecondary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
                            )
                        }
                    } else {
                        items(visibleTasks, key = AiTaskEntity::id, contentType = { "ai_task" }) { task ->
                            AiTaskRow(
                                task = task,
                                isMutating = task.id in state.mutatingTaskIds,
                                onCompletedChange = { completed -> viewModel.setCompleted(task, completed) },
                                onAddToCalendar = { openTaskInCalendar(context, task) },
                                onDelete = { taskToDelete = task }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
                        }
                    }
                }
            }
        }
    }

    taskToDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { taskToDelete = null },
            title = { Text(stringResource(R.string.ai_tasks_delete_confirm_title)) },
            text = { Text(stringResource(R.string.ai_tasks_delete_confirm_message, task.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        taskToDelete = null
                        viewModel.delete(task)
                    }
                ) {
                    Text(stringResource(R.string.ai_tasks_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { taskToDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
    } // secret watermark Box
    } // unlocked branch
}

@Composable
private fun AiTaskSummary(pendingCount: Int, completedCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            stringResource(R.string.ai_tasks_pending_count, pendingCount),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            stringResource(R.string.ai_tasks_completed_count, completedCount),
            style = MaterialTheme.typography.labelLarge,
            color = LocalChatPalette.current.textSecondary
        )
    }
}
