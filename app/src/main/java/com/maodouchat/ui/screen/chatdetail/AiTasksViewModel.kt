package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.data.local.entity.AiTaskEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AiTasksViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    val chatId: String = savedStateHandle["chatId"] ?: ""
    // U02 延伸：仓库入口收进非 ui 的 AppRepositories（不再从 Application 强转后自取 database）。
    private val repository = com.maodouchat.data.repository.AppRepositories.aiTasks(application)
    private val chatLockRepo = com.maodouchat.data.repository.AppRepositories.chatLocks

    /** Capture at open so logout/account switch cannot mutate the next owner's tasks. */
    private val ownerUserId: String = com.maodouchat.session.CurrentSession.ownerUserId()
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
        )
    }

    private suspend fun resolveChatName(): String {
        return try {
            val entity = com.maodouchat.data.repository.AppRepositories.chatEntityOrNull(chatId) ?: return ""
            entity.groupName?.takeIf { it.isNotBlank() }
                ?: entity.participantIds
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it != ownerUserId }
                    .firstOrNull()
                    ?.let { peerId ->
                        com.maodouchat.data.repository.AppRepositories.users.getUserById(peerId)?.let { u ->
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
        val caps = try { com.maodouchat.security.SecretChatCapabilities.forChat(chatId) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { com.maodouchat.domain.messaging.ConversationPrivacyCapabilities(isSecretChat = true, isLocked = false) }
        val locked = caps.isLocked
        val secret = caps.isSecretChat
        if (secret) {
            com.maodouchat.security.SecretChatSession.markSurfaceActive(chatId)
        } else {
            com.maodouchat.security.SecretChatSession.clearSurfaceMarker(chatId)
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
        com.maodouchat.notification.NotificationCenterAccess.repository.markAiTasksRead(chatId)
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
