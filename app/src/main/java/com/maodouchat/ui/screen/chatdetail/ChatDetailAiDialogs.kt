package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.maodouchat.R
import com.maodouchat.ai.AiWeeklyReport
import com.maodouchat.data.model.Message

/**
 * 聊天页的 **AI 相关对话框簇**（G74 从 `ChatDetailRoute.kt` 拆出）。
 *
 * 这是 `ChatDetailRoute`（5000+ 行）拆出的第一个 Composable 簇，覆盖 10 个对话框：
 * AI 同意、摘要范围选择、摘要历史、摘要结果、会话画像、周报、消息分类、
 * 图片分析结果、文件分析结果、群 AI 助手（含「确认分享」二级对话框）。
 *
 * **拆解约束（与本项目其它端口化改动一致）**：
 * - 本文件不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * - 所有输入经参数显式传入（状态 + 回调），因此它可以在没有真实 ViewModel 的情况下被预览/测试；
 * - 纯搬移，不改变任何判断——每个 `if` 的条件与原先一字不差。
 *
 * @param showAiSummaryScopeDialog / showConversationProfile / showWeeklyReport / showMessageClassify
 *        这四个开关原先由 Route 用 `rememberSaveable` 持有，其 setter 经 [onDismissAiSummaryScope] 等回调传回，
 *        这样开关的所有权仍在 Route（跨进程恢复语义不变）。
 */
@Composable
internal fun ChatDetailAiDialogs(
    state: ChatDetailUiState,
    viewModel: ChatDetailViewModel,
    searchResults: List<Message>,
    showSearchBar: Boolean,
    showAiSummaryScopeDialog: Boolean,
    onDismissAiSummaryScope: () -> Unit,
    showConversationProfile: Boolean,
    onDismissConversationProfile: () -> Unit,
    conversationProfile: com.maodouchat.ai.AiConversationProfile.ConversationProfile?,
    conversationProfileLoading: Boolean,
    conversationProfileFailed: Boolean,
    showWeeklyReport: Boolean,
    onDismissWeeklyReport: () -> Unit,
    weeklyReport: AiWeeklyReport.WeeklyReport?,
    weeklyReportLoading: Boolean,
    weeklyReportFailed: Boolean,
    showMessageClassify: Boolean,
    onDismissMessageClassify: () -> Unit,
    chatClassifications: List<com.maodouchat.data.repository.AiProfileRepository.CategoryCount>,
    classifyLoading: Boolean,
    classifyFailed: Boolean,
) {
    val context = LocalContext.current
    val chatCopiedMsg = stringResource(R.string.chat_copied)
    val chatGroupAiTitle = stringResource(R.string.chat_group_ai_title)
    val chatGroupAiCopiedMsg = stringResource(R.string.chat_group_ai_copied)
    val chatAiImageResultTitle = stringResource(R.string.chat_ai_image_result_title)
    val chatAiFileResultTitle = stringResource(R.string.chat_ai_file_result_title)

    /** 剪贴板复制的公共收口（原先 6 处各写一遍，这里收成一个函数，行为不变）。 */
    val copyToClipboard: (String, String) -> Unit = { label, text ->
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        android.widget.Toast.makeText(context, chatCopiedMsg, android.widget.Toast.LENGTH_SHORT).show()
    }

    if (state.showAiConsentDialog) {
        AiConsentDialog(
            onAccept = { viewModel.acceptAiConsentAndContinue() },
            onDismiss = { viewModel.dismissAiConsent() }
        )
    }

    if (showAiSummaryScopeDialog) {
        AiSummaryScopeDialog(
            searchResultCount = if (showSearchBar) searchResults.size else 0,
            onDismiss = onDismissAiSummaryScope,
            onSelect = { scope, style ->
                onDismissAiSummaryScope()
                viewModel.requestAiSummary(
                    scope = scope,
                    searchResultIds = if (scope == AiSummaryScope.SEARCH_RESULTS) searchResults.map(Message::id) else emptyList(),
                    style = style
                )
            }
        )
    }

    if (state.showAiSummaryHistory) {
        AiSummaryHistoryDialog(
            summaries = state.aiSummaryHistory,
            isLoading = state.isAiSummaryHistoryLoading,
            onSelect = viewModel::openAiSummaryFromHistory,
            onDismiss = viewModel::dismissAiSummaryHistory
        )
    }

    state.aiSummary?.let { summary ->
        AiSummaryDialog(
            summary = summary,
            scope = state.aiSummaryScope ?: AiSummaryScope.RECENT,
            messageCount = state.aiSummaryMessageCount,
            onDismiss = { viewModel.clearAiSummary() }
        )
    }

    // B4：会话画像对话框（本地统计 + 可选叙事摘要）
    if (showConversationProfile) {
        ConversationProfileDialog(
            loading = conversationProfileLoading,
            profile = conversationProfile,
            failed = conversationProfileFailed,
            onDismiss = onDismissConversationProfile,
            // 1.317：复制会话画像
            onCopyProfile = { profileText ->
                copyToClipboard(context.getString(R.string.chat_ai_conversation_profile_title), profileText)
            }
        )
    }

    // B4：本周周报对话框（生成并缓存到本地）
    if (showWeeklyReport) {
        WeeklyReportDialog(
            loading = weeklyReportLoading,
            report = weeklyReport,
            failed = weeklyReportFailed,
            onDismiss = onDismissWeeklyReport,
            // 1.310：复制周报全文
            onCopyReport = { reportText ->
                copyToClipboard(context.getString(R.string.chat_ai_weekly_report_title), reportText)
            }
        )
    }

    // 8.47：消息分类统计对话框（纯本地）
    if (showMessageClassify) {
        MessageClassifyDialog(
            loading = classifyLoading,
            categories = chatClassifications,
            failed = classifyFailed,
            onDismiss = onDismissMessageClassify,
            // 1.349：复制分类结果（与周报/画像复制一致）
            onCopyClassify = { classifyText ->
                copyToClipboard(context.getString(R.string.ai_enhance_classify_title), classifyText)
            }
        )
    }

    state.aiImageAnalysisResult?.let { result ->
        AiImageAnalysisResultDialog(
            result = result,
            mode = state.aiImageAnalysisMode ?: AiImageAnalysisMode.DESCRIBE,
            onCopy = { copyToClipboard(chatAiImageResultTitle, result) },
            onDismiss = viewModel::clearAiImageAnalysis
        )
    }

    state.aiFileAnalysisResult?.let { result ->
        AiFileAnalysisResultDialog(
            result = result,
            fileName = state.aiFileAnalysisName.orEmpty(),
            mode = state.aiFileAnalysisMode ?: AiFileAnalysisMode.SUMMARIZE,
            onCopy = { copyToClipboard(chatAiFileResultTitle, result) },
            onDismiss = viewModel::clearAiFileAnalysis
        )
    }

    var confirmShareGroupAi by remember { mutableStateOf(false) }
    state.groupAiAnswer?.let { answer ->
        val canShare = !state.groupAiAnswerShared &&
            com.maodouchat.ai.GroupAiSharePolicy.decideShare(
                isGroup = state.chatIsGroup || state.chat?.isGroup == true,
                answer = answer,
                alreadyShared = state.groupAiAnswerShared
            ).allowed
        GroupAiAssistantDialog(
            question = state.groupAiQuestion,
            answer = answer,
            tasks = state.groupAiTasks,
            isSavingTasks = state.isSavingGroupAiTasks,
            tasksSaved = state.groupAiTasksSaved,
            taskSaveError = state.groupAiTaskSaveError,
            shareEnabled = canShare,
            onCopy = { copyToClipboard(chatGroupAiTitle, answer) },
            onSaveTasks = { viewModel.saveGroupAiTasks() },
            onShare = { if (canShare) confirmShareGroupAi = true },
            onDismiss = {
                confirmShareGroupAi = false
                viewModel.clearGroupAiAnswer()
            }
        )
        if (confirmShareGroupAi && canShare) {
            AlertDialog(
                onDismissRequest = { confirmShareGroupAi = false },
                title = { Text(stringResource(R.string.chat_group_ai_share_confirm_title)) },
                text = { Text(stringResource(R.string.chat_group_ai_share_confirm_body)) },
                confirmButton = {
                    Button(onClick = {
                        confirmShareGroupAi = false
                        viewModel.shareGroupAiAnswer()
                    }) { Text(stringResource(R.string.chat_group_ai_share_confirm_action)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmShareGroupAi = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }
    }
}
