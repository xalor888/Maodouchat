package com.maodouchat.ui.screen.chatdetail

import androidx.lifecycle.viewModelScope
import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 草稿持久化（自 ChatDetailViewModel.kt 拆分）。
// 涵盖恢复/清除/防抖保存草稿，复用 chatDraftDao、draftSaveJob 与 ChatDraftPolicy。

internal fun ChatDetailViewModel.restoreDraft() {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CHAT_DRAFTS)) return
    val ownerUserId = tokenManager.getUserId().orEmpty()
    if (!ChatDraftPolicy.canSchedule(ownerUserId, activeChatId)) return
    viewModelScope.launch(Dispatchers.IO) {
        val draft = chatDraftDao.get(ownerUserId, activeChatId)?.text.orEmpty()
        // Account switch between load and UI apply: never inject previous owner's draft.
        if (!ChatDraftPolicy.shouldWrite(ownerUserId, tokenManager.getUserId())) return@launch
        if (draft.isNotBlank() && !hasUserEditedInput) {
            _uiState.update { state ->
                if (!ChatDraftPolicy.shouldApplyRestoredDraft(hasUserEditedInput, state.inputText)) state
                else state.copy(inputText = draft, hasSavedDraft = true)
            }
        }
    }
}

/** 1.162：清空已恢复草稿（本地删除 + 清除标识）。 */
internal fun ChatDetailViewModel.clearDraftPersistence() {
    val ownerUserId = tokenManager.getUserId().orEmpty()
    if (ownerUserId.isBlank() || activeChatId.isBlank()) return
    draftSaveJob?.cancel()
    viewModelScope.launch(Dispatchers.IO) {
        try {
            chatDraftDao.delete(ownerUserId, activeChatId)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }
    _uiState.update { it.copy(hasSavedDraft = false) }
}

internal fun ChatDetailViewModel.scheduleDraftPersistence(text: String) {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CHAT_DRAFTS)) return
    val ownerUserId = tokenManager.getUserId().orEmpty()
    val targetChatId = activeChatId
    if (!ChatDraftPolicy.canSchedule(ownerUserId, targetChatId)) return
    draftSaveJob?.cancel()
    val generation = ++draftGeneration
    draftSaveJob = viewModelScope.launch(Dispatchers.IO) {
        delay(ChatDraftPolicy.SAVE_DELAY_MS)
        if (!ChatDraftPolicy.shouldPersistGeneration(generation, draftGeneration)) return@launch
        if (!ChatDraftPolicy.shouldWrite(ownerUserId, tokenManager.getUserId())) return@launch
        // 9.134：persistDraft 此前与 return@launch 同行——恒不可达，防抖保存从未执行，
        // 草稿只在 onCleared 时落盘（进程被杀即丢）
        persistDraft(ownerUserId, targetChatId, text)
    }
}

internal suspend fun ChatDetailViewModel.persistDraft(ownerUserId: String, targetChatId: String, text: String) {
    if (!ChatDraftPolicy.shouldWrite(ownerUserId, tokenManager.getUserId())) return
    if (ChatDraftPolicy.isClearRequest(text)) {
        chatDraftDao.delete(ownerUserId, targetChatId)
    } else {
        chatDraftDao.upsert(
            ChatDraftEntity(
                ownerUserId = ownerUserId,
                chatId = targetChatId,
                text = text,
                updatedAt = System.currentTimeMillis()
            )
        )
    }
}

internal fun ChatDetailViewModel.clearDraft() {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CHAT_DRAFTS)) return
    val ownerUserId = tokenManager.getUserId().orEmpty()
    val targetChatId = activeChatId
    draftSaveJob?.cancel()
    draftSaveJob = null
    // Invalidate any in-flight delayed upsert so send cannot resurrect draft after clear.
    draftGeneration++
    if (!ChatDraftPolicy.canSchedule(ownerUserId, targetChatId)) return
    viewModelScope.launch(Dispatchers.IO) {
        withContext(NonCancellable) {
            chatDraftDao.delete(ownerUserId, targetChatId)
        }
    }
}
