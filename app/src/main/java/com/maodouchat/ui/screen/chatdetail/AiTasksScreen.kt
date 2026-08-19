package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.util.RuntimeFlags
import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.data.local.entity.AiTaskEntity
import com.maodouchat.data.repository.AiTaskRepository
import com.maodouchat.ui.component.EmptyState
import com.maodouchat.ui.component.EmptyStateType
import com.maodouchat.ui.component.blindWatermark
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Outline
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextHint
import com.maodouchat.ui.theme.TextSecondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

data class AiTasksUiState(
    val tasks: List<AiTaskEntity> = emptyList(),
    val isLoading: Boolean = true,
    val mutatingTaskIds: Set<String> = emptySet(),
    val error: String? = null,
    /** null while checking; true when PIN required and process not unlocked */
    val isChatLocked: Boolean? = null,
    val chatName: String = "",
    val isSecretChat: Boolean = false,
)

class AiTasksViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    val chatId: String = savedStateHandle["chatId"] ?: ""
    private val app = application as MaodouchatApp
    private val repository = AiTaskRepository(app.database.aiTaskDao(), application)
    private val chatLockRepo = com.maodouchat.data.repository.ChatLockRepository(app.database.chatLockDao())
    private val secretChatRepo = com.maodouchat.data.repository.SecretChatRepository(app.database.secretChatDao())
    private val tokenManager = com.maodouchat.network.TokenManager.getInstance(application)
    /** Capture at open so logout/account switch cannot mutate the next owner's tasks. */
    private val ownerUserId: String = tokenManager.getUserId().orEmpty()
    private val _uiState = MutableStateFlow(AiTasksUiState())
    val uiState: StateFlow<AiTasksUiState> = _uiState.asStateFlow()

    /** 8.38：任务列表订阅 job——重新订阅前取消旧 collector，防重复 Room 订阅。 */
    private var observeTasksJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            refreshLockThenObserve()
        }
    }

    /** 8.52 UX：加载失败后手动重试（错误空态的重试按钮）。 */
    fun reloadTasks() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            refreshLockThenObserve()
        }
    }

    private fun text(id: Int): String = getApplication<Application>().getString(id)

    private fun sessionStillOwned(): Boolean {
        if (ownerUserId.isBlank()) return false
        return com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = ownerUserId,
            liveToken = tokenManager.getToken(),
            liveUserId = tokenManager.getUserId(),
        )
    }

    private suspend fun resolveChatName(): String {
        return try {
            val entity = app.database.chatDao().getChatById(chatId) ?: return ""
            entity.groupName?.takeIf { it.isNotBlank() }
                ?: entity.participantIds
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it != ownerUserId }
                    .firstOrNull()
                    ?.let { peerId ->
                        app.database.userDao().getUserById(peerId)?.let { u ->
                            u.nickname?.takeIf { it.isNotBlank() } ?: u.name
                        }
                    }
                ?: ""
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
    }

    private suspend fun refreshLockThenObserve() {
        if (chatId.isBlank()) {
            _uiState.update { it.copy(isLoading = false, isChatLocked = false, error = text(R.string.ai_tasks_load_failed)) }
            return
        }
        if (!sessionStillOwned()) {
            _uiState.update {
                it.copy(isLoading = false, tasks = emptyList(), isChatLocked = false, error = text(R.string.error_session_expired))
            }
            return
        }
        val locked = try { chatLockRepo.get(chatId) != null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { false }
        val secret = try { secretChatRepo.isSecret(chatId) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { false }
        if (secret) {
            com.maodouchat.security.SecretChatSession.markSurfaceActive(chatId)
        } else {
            com.maodouchat.security.SecretChatSession.markSurfaceInactive(chatId, getApplication())
        }
        val unlocked = !locked || com.maodouchat.security.ChatLockSession.isUnlocked(chatId)
        val displayName = resolveChatName()
        if (!unlocked) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    tasks = emptyList(),
                    isChatLocked = true,
                    chatName = displayName,
                    isSecretChat = secret,
                    error = null,
                )
            }
            return
        }
        _uiState.update { it.copy(isChatLocked = false, chatName = displayName, isSecretChat = secret) }
        observeTasks()
    }

    fun unlockWithPin(pin: String, onResult: (Boolean) -> Unit) {
        if (chatId.isBlank()) {
            onResult(true)
            return
        }
        viewModelScope.launch {
            val ok = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    chatLockRepo.verify(chatId, pin)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (ok) {
                com.maodouchat.security.ChatLockSession.markUnlocked(chatId)
                _uiState.update { it.copy(isChatLocked = false, isLoading = true) }
                observeTasks()
            }
            onResult(ok)
        }
    }

    private fun observeTasks() {
        if (chatId.isBlank()) {
            _uiState.update { it.copy(isLoading = false, error = text(R.string.ai_tasks_load_failed)) }
            return
        }
        if (!sessionStillOwned()) {
            _uiState.update {
                it.copy(isLoading = false, tasks = emptyList(), error = text(R.string.error_session_expired))
            }
            return
        }
        // 真正开始展示任务时，把通知中心里该会话的 AI_TASK 行标为已读。
        app.notificationCenter.markAiTasksRead(chatId)
        // 8.38：先取消旧订阅——解锁/加锁切换会再次调用 observeTasks，
        // 此前每个 collector 都 launchIn(viewModelScope) 永不清除，导致重复 Room 订阅与
        // 并发状态写入（删除任务时 mutatingTaskIds 竞态窗口变大）
        observeTasksJob?.cancel()
        observeTasksJob = repository.observeTasks(chatId)
            .onEach { tasks ->
                if (!sessionStillOwned()) {
                    _uiState.update {
                        it.copy(isLoading = false, tasks = emptyList(), error = text(R.string.error_session_expired))
                    }
                    return@onEach
                }
                if (try { chatLockRepo.get(chatId) != null }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { false } &&
                    !com.maodouchat.security.ChatLockSession.isUnlocked(chatId)
                ) {
                    _uiState.update {
                        it.copy(isLoading = false, tasks = emptyList(), isChatLocked = true)
                    }
                    return@onEach
                }
                _uiState.update { it.copy(tasks = tasks, isLoading = false, error = null, isChatLocked = false) }
            }
            .catch {
                _uiState.update { it.copy(isLoading = false, error = text(R.string.ai_tasks_load_failed)) }
            }
            .launchIn(viewModelScope)
    }

    fun setCompleted(task: AiTaskEntity, completed: Boolean) {
        mutate(task.id, R.string.ai_tasks_update_failed) {
            repository.setCompleted(task.id, completed)
        }
    }

    fun delete(task: AiTaskEntity) {
        mutate(task.id, R.string.ai_tasks_delete_failed) {
            repository.delete(task.id)
        }
    }

    /** 1.304：清空本会话全部已完成任务。 */
    fun clearCompleted() {
        if (chatId.isBlank()) return
        if (!sessionStillOwned()) {
            _uiState.update { it.copy(error = text(R.string.error_session_expired)) }
            return
        }
        viewModelScope.launch {
            try {
                if (!sessionStillOwned()) {
                    _uiState.update { it.copy(error = text(R.string.error_session_expired)) }
                    return@launch
                }
                repository.deleteCompletedByChatId(chatId)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(error = text(R.string.ai_tasks_clear_completed_failed)) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun mutate(taskId: String, errorResource: Int, operation: suspend () -> Unit) {
        if (taskId in _uiState.value.mutatingTaskIds) return
        if (!sessionStillOwned()) {
            _uiState.update { it.copy(error = text(R.string.error_session_expired)) }
            return
        }
        _uiState.update { it.copy(mutatingTaskIds = it.mutatingTaskIds + taskId, error = null) }
        viewModelScope.launch {
            try {
                if (!sessionStillOwned()) {
                    _uiState.update {
                        it.copy(
                            mutatingTaskIds = it.mutatingTaskIds - taskId,
                            error = text(R.string.error_session_expired)
                        )
                    }
                    return@launch
                }
                operation()
                if (!sessionStillOwned()) {
                    _uiState.update {
                        it.copy(
                            mutatingTaskIds = it.mutatingTaskIds - taskId,
                            error = text(R.string.error_session_expired)
                        )
                    }
                    return@launch
                }
                _uiState.update { it.copy(mutatingTaskIds = it.mutatingTaskIds - taskId) }
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(mutatingTaskIds = it.mutatingTaskIds - taskId) }
                throw error
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        mutatingTaskIds = it.mutatingTaskIds - taskId,
                        error = text(errorResource)
                    )
                }
            }
        }
    }
}

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
            com.maodouchat.util.AppNotifier.cancelAiTaskRemindersForChat(context.applicationContext, chatId)
            // 8.48 修复：连同 WorkManager 提醒作业一并取消——此前只清托盘，到点仍会弹新通知
            val app = context.applicationContext as? com.maodouchat.MaodouchatApp ?: return@LaunchedEffect
            com.maodouchat.MaodouchatApp.instance.applicationScope.launch {
                try {
                    app.database.aiTaskDao().getIdsByChatId(chatId).forEach { taskId ->
                        com.maodouchat.ai.AiTaskReminderScheduler.cancelTask(app, taskId)
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // 提醒取消失败不阻塞任务页打开
                }
            }
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val secretLabel = com.maodouchat.ui.component.rememberSecretBlindWatermarkLabel(
        userId = com.maodouchat.network.TokenManager.getInstance(context).getUserId(),
        chatId = viewModel.chatId,
        deviceHint = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ),
        enabled = state.isSecretChat
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
                    tint = Primary
                )
            }
        }
    } else {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (state.isSecretChat && secretLabel.isNotBlank()) {
                    Modifier.blindWatermark(label = secretLabel, enabled = RuntimeFlags.isEnabled(LocalContext.current, RuntimeFlags.VISIBLE_WATERMARK))
                } else Modifier
            )
    ) {
    Scaffold(
        containerColor = LocalChatPalette.current.chatBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_tasks_title), color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = Primary
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
                                tint = Primary
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
                CircularProgressIndicator(color = Primary)
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
                                color = TextSecondary,
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
                            HorizontalDivider(color = Outline.copy(alpha = 0.55f))
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

private enum class AiTaskFilter { ALL, PENDING, COMPLETED }

@Composable
private fun AiTaskSummary(pendingCount: Int, completedCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Primary.copy(alpha = 0.08f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            stringResource(R.string.ai_tasks_pending_count, pendingCount),
            style = MaterialTheme.typography.labelLarge,
            color = Primary
        )
        Text(
            stringResource(R.string.ai_tasks_completed_count, completedCount),
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary
        )
    }
}

@Composable
private fun AiTaskFilterStrip(
    selected: AiTaskFilter,
    onSelect: (AiTaskFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = listOf(
        AiTaskFilter.ALL to stringResource(R.string.ai_tasks_filter_all),
        AiTaskFilter.PENDING to stringResource(R.string.ai_tasks_filter_pending),
        AiTaskFilter.COMPLETED to stringResource(R.string.ai_tasks_filter_completed)
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

@Composable
private fun AiTaskRow(
    task: AiTaskEntity,
    isMutating: Boolean,
    onCompletedChange: (Boolean) -> Unit,
    onAddToCalendar: () -> Unit,
    onDelete: () -> Unit
) {
    val rowColor by animateColorAsState(
        targetValue = if (task.isCompleted) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surface,
        animationSpec = tween(220),
        label = "taskRowColor"
    )
    val formattedDueAt = remember(task.dueAt) {
        task.dueAt?.let {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
        }
    }
    val dueLabel = remember(task.dueText, formattedDueAt) {
        listOfNotNull(task.dueText?.takeIf(String::isNotBlank), formattedDueAt)
            .distinct()
            .joinToString(" · ")
            .takeIf(String::isNotBlank)
    }
    val dueColor = if (!task.isCompleted && task.dueAt != null && task.dueAt < System.currentTimeMillis()) {
        MaterialTheme.colorScheme.error
    } else {
        TextSecondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(220))
            .background(rowColor)
            .padding(start = 8.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        val impulse by animateFloatAsState(
            targetValue = if (task.isCompleted) 1.1f else 1f,
            animationSpec = spring(dampingRatio = 0.45f, stiffness = 460f),
            label = "taskCheckScale"
        )
        Checkbox(
            checked = task.isCompleted,
            onCheckedChange = onCompletedChange,
            enabled = !isMutating,
            modifier = Modifier.graphicsLayer {
                scaleX = impulse
                scaleY = impulse
            }
        )
        Column(
            modifier = Modifier.weight(1f).padding(top = 3.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (task.isCompleted) TextHint else OnSurface,
                fontWeight = if (task.isCompleted) FontWeight.Normal else FontWeight.Medium,
                textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            task.owner?.takeIf(String::isNotBlank)?.let { owner ->
                AiTaskMetadata(
                    icon = { Icon(Icons.Outlined.PersonOutline, contentDescription = null, modifier = Modifier.size(15.dp)) },
                    text = stringResource(R.string.ai_tasks_owner, owner),
                    color = TextSecondary
                )
            }
            dueLabel?.let { due ->
                AiTaskMetadata(
                    icon = { Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(15.dp)) },
                    text = stringResource(R.string.ai_tasks_due, due),
                    color = dueColor
                )
            }
        }
        if (isMutating) {
            Box(modifier = Modifier.size(if (task.dueAt != null) 80.dp else 40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Primary)
            }
        } else {
            Row {
                if (task.dueAt != null) {
                    IconButton(onClick = onAddToCalendar, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Outlined.CalendarMonth,
                            contentDescription = stringResource(R.string.ai_tasks_add_to_calendar),
                            tint = Primary
                        )
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = stringResource(R.string.ai_tasks_delete),
                        tint = TextHint
                    )
                }
            }
        }
    }
}

private fun openTaskInCalendar(context: Context, task: AiTaskEntity) {
    val dueAt = task.dueAt ?: return
    val description = buildString {
        task.owner?.takeIf(String::isNotBlank)?.let {
            append(context.getString(R.string.ai_tasks_owner, it))
        }
        task.sourceQuery.takeIf(String::isNotBlank)?.let {
            if (isNotEmpty()) append('\n')
            append(context.getString(R.string.ai_tasks_source, it))
        }
    }
    val intent = Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        putExtra(CalendarContract.Events.TITLE, task.title)
        putExtra(CalendarContract.Events.DESCRIPTION, description)
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, dueAt)
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, dueAt + 30 * 60_000L)
    }
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, context.getString(R.string.ai_tasks_calendar_unavailable), Toast.LENGTH_SHORT).show()
        }
}

@Composable
private fun AiTaskMetadata(
    icon: @Composable () -> Unit,
    text: String,
    color: androidx.compose.ui.graphics.Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides color) {
            icon()
        }
        Spacer(modifier = Modifier.width(5.dp))
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun AiTasksEmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Icon(Icons.Outlined.Checklist, contentDescription = null, tint = TextHint, modifier = Modifier.size(44.dp))
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                stringResource(R.string.ai_tasks_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.ai_tasks_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = TextHint
            )
        }
    }
}
