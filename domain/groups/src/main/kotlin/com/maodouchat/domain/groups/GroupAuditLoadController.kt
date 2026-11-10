package com.maodouchat.domain.groups

import com.maodouchat.core.model.GroupDetailUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 群审计分页加载（offset 语义），追加去重后写入审计列表。 */
class GroupAuditLoadController(
    private val scope: CoroutineScope,
    private val currentState: () -> GroupDetailUiState,
    private val updateState: ((GroupDetailUiState) -> GroupDetailUiState) -> Unit,
    private val chatId: () -> String,
    private val ownerUserId: () -> String,
    private val sessionActive: (String) -> Boolean,
    private val groupAuditController: GroupAuditController,
    private val auditOffsetGet: () -> Int,
    private val auditOffsetSet: (Int) -> Unit,
) {
    fun loadMoreAudit() {
        if (currentState().isLoadingMoreAudit || !currentState().hasMoreAudit) return
        val auditOwnerUserId = ownerUserId()
        if (auditOwnerUserId.isBlank()) return
        updateState { it.copy(isLoadingMoreAudit = true) }
        scope.launch {
            try {
                if (!sessionActive(auditOwnerUserId)) {
                    updateState { it.copy(isLoadingMoreAudit = false) }
                    return@launch
                }
                val offset = auditOffsetGet()
                val page = groupAuditController.fetchAuditLogs(chatId(), limit = 100, offset = offset).getOrNull().orEmpty()
                auditOffsetSet(offset + page.size)
                if (!sessionActive(auditOwnerUserId)) {
                    return@launch
                }
                updateState { st ->
                    if (page.isEmpty()) {
                        st.copy(isLoadingMoreAudit = false, hasMoreAudit = false)
                    } else {
                        st.copy(
                            auditLogs = (st.auditLogs + page).distinctBy { it.id },
                            isLoadingMoreAudit = false,
                            hasMoreAudit = page.size >= 100
                        )
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentAuditOwner(auditOwnerUserId)) updateState { it.copy(isLoadingMoreAudit = false) }
                throw error
            } catch (error: Exception) {
                if (isCurrentAuditOwner(auditOwnerUserId)) updateState { it.copy(isLoadingMoreAudit = false, message = error.message?.take(120)) }
            }
        }
    }

    private fun isCurrentAuditOwner(expected: String): Boolean =
        expected.isNotBlank() && ownerUserId() == expected
}
