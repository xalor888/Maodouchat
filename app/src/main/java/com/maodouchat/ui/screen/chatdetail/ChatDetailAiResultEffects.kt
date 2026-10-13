package com.maodouchat.ui.screen.chatdetail

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.maodouchat.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * G389：B4 本地 AI 聚合副作用：消息分类 / 情感回复 / 会话画像 / 本周周报（纯搬移，不改判断）。
 * 仅非密聊会话触发（密聊不参与，避免落可搜索缓存）。
 * 依赖全经参数注入；组合期内不新增状态所有权。
 */
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在协程内读取，非组合作用域；lint 无法区分
@Composable
internal fun ChatDetailAiResultEffects(
    aiPanels: ChatDetailAiPanelState,
    aiResults: ChatDetailAiResultState,
    state: ChatDetailUiState,
    viewModel: ChatDetailViewModel,
) {
    val context = LocalContext.current
    // 8.47：消息分类（纯本地词典统计）
    LaunchedEffect(aiPanels.showMessageClassify, state.chat?.id) {
        if (!aiPanels.showMessageClassify) return@LaunchedEffect
        val chatId = state.chat?.id ?: return@LaunchedEffect
        aiResults.classifyLoading = true
        aiResults.classifyFailed = false
        aiResults.chatClassifications = emptyList()
        // G73：经端口调用，不再自己抓 app 数据库单例
        val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { viewModel.aiChatClassificationSource.classify(chatId) }
        }
        aiResults.classifyLoading = false
        aiResults.classifyFailed = result.isFailure
        result.getOrNull()?.let { aiResults.chatClassifications = it }
    }
    LaunchedEffect(aiResults.emotionReplyRequested, state.chat?.id) {
        if (!aiResults.emotionReplyRequested) return@LaunchedEffect
        aiResults.emotionReplyRequested = false
        val chatId = state.chat?.id ?: return@LaunchedEffect
        // G73：经端口调用，不再自己抓 app 数据库单例
        val reply = withContext(kotlinx.coroutines.Dispatchers.IO) {
            viewModel.aiEmotionReplySource.reply(chatId)
        }
        if (reply.isNotBlank()) {
            viewModel.onInputChange(reply)
            Toast.makeText(context, context.getString(R.string.chat_ai_emotion_reply_generated), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, context.getString(R.string.chat_ai_emotion_reply_failed), Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(aiPanels.showConversationProfile, state.chat?.id) {
        if (!aiPanels.showConversationProfile) return@LaunchedEffect
        val chatId = state.chat?.id ?: return@LaunchedEffect
        aiResults.conversationProfileLoading = true
        aiResults.conversationProfileFailed = false
        // G73：经 ViewModel 暴露的端口调用，不再自己抓 app 数据库单例
        val built = withContext(kotlinx.coroutines.Dispatchers.IO) {
            viewModel.aiConversationProfileSource.build(chatId)
        }
        if (built.local.messageCount > 0 || !built.narrative.isNullOrBlank()) {
            aiResults.conversationProfile = built
        } else {
            aiResults.conversationProfileFailed = true
        }
        aiResults.conversationProfileLoading = false
    }
    LaunchedEffect(aiPanels.showWeeklyReport, state.chat?.id) {
        if (!aiPanels.showWeeklyReport) return@LaunchedEffect
        val chatId = state.chat?.id ?: return@LaunchedEffect
        aiResults.weeklyReportLoading = true
        aiResults.weeklyReportFailed = false
        // G73：经端口调用，不再自己抓 app 数据库单例
        aiResults.weeklyReport = withContext(kotlinx.coroutines.Dispatchers.IO) {
            viewModel.aiWeeklyReportSource.generate(chatId)
        }
        aiResults.weeklyReportFailed = aiResults.weeklyReport == null
        aiResults.weeklyReportLoading = false
    }
}
