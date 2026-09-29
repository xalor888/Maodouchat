package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.group.GroupAuditController
import com.maodouchat.group.GroupDetailUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * G370：群审计分页加载从 `GroupDetailViewModel` 抽出（纯搬移不改判断）——
 * `auditNextOffset` 仍由 VM 持有（loader 也写它），经 get/set lambda 共享。
 */
internal class GroupAuditLoadController(
    private val scope: CoroutineScope,
    private val currentState: () -> GroupDetailUiState,
    private val updateState: ((GroupDetailUiState) -> GroupDetailUiState) -> Unit,
    private val chatId: () -> String,
    private val ownerUserId: () -> String,
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
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = auditOwnerUserId,
                )
                ) {
                    updateState { it.copy(isLoadingMoreAudit = false) }
                    return@launch
                }
                val offset = auditOffsetGet()
                val page = groupAuditController.fetchAuditLogs(chatId(), limit = 100, offset = offset).getOrNull().orEmpty()
                auditOffsetSet(offset + page.size)
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = auditOwnerUserId,
                )
                ) {
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
        expected.isNotBlank() && com.maodouchat.session.CurrentSession.snapshot().userId == expected
}
