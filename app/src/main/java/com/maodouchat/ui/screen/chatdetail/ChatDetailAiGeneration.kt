package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.util.RuntimeFlags
import com.maodouchat.R
import com.maodouchat.ai.AiCostVisibilityPolicy
import com.maodouchat.ai.AiPromptSafetyPolicy
import com.maodouchat.ai.AiRetryPolicy
import com.maodouchat.ai.AiWritingStylePreferences
import com.maodouchat.data.local.entity.AiOperationState
import com.maodouchat.data.local.entity.AiOperationError
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageType
import com.maodouchat.network.ApiService
import com.maodouchat.network.UnreadWindowDto
import com.maodouchat.network.AiContextMessage
import com.maodouchat.network.AiSemanticSearchCandidate
import com.maodouchat.data.model.semanticSearchText
import com.maodouchat.util.MediaCache
import com.maodouchat.util.ImagePicker
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import android.net.Uri

/**
 * 从 ChatDetailViewModel.kt 拆分的 AI 生成函数。
 * 包含群助手、语义搜索、改写、回复建议、摘要、语音转写、图片/文件分析、翻译等。
 * 作为 ChatDetailViewModel 的扩展函数，访问 internal 成员。
 */

internal data class AiRequestSnapshot(val userId: String, val chatId: String)

internal fun ChatDetailViewModel.captureAiRequestSnapshot(): AiRequestSnapshot? {
    val userId = tokenManager.getUserId().orEmpty()
    val chatId = activeChatId
    val state = _uiState.value
    if (chatId.isBlank() || state.currentUserId != userId || state.chat?.id != chatId ||
        !com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = userId,
            liveToken = tokenManager.getToken(),
            liveUserId = tokenManager.getUserId(),
        )
    ) {
        return null
    }
    return AiRequestSnapshot(userId = userId, chatId = chatId)
}

internal fun ChatDetailViewModel.isAiRequestCurrent(snapshot: AiRequestSnapshot): Boolean =
    activeChatId == snapshot.chatId &&
        _uiState.value.currentUserId == snapshot.userId &&
        _uiState.value.chat?.id == snapshot.chatId &&
        com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = snapshot.userId,
            liveToken = tokenManager.getToken(),
            liveUserId = tokenManager.getUserId(),
        )

internal fun ChatDetailViewModel.requireAiRequestCurrent(snapshot: AiRequestSnapshot) {
    if (!isAiRequestCurrent(snapshot)) {
        throw kotlinx.coroutines.CancellationException("ai_request_context_changed")
    }
}

    internal fun ChatDetailViewModel.groupAssistant(query: String, mode: String) {
        // 密聊会话禁止群 AI 助手：解密明文不得送服务端 AI
        if (_uiState.value.isSecretChat == true) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_ai_blocked)) }
            return
        }
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_GROUP_ASSISTANT)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.ai_group_assistant_disabled)) }
            return
        }
        val contextMessages = buildGroupAiContextMessages()
        if (contextMessages.isEmpty()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_group_ai_no_context)) }
            return
        }
        val request = captureAiRequestSnapshot()
        if (request == null) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        groupAiJob?.cancel()
        val generation = groupAiGate.next()
        val currentCommand = parseGroupAiCommand(_uiState.value.inputText)
        _uiState.update {
            it.copy(
                inputText = if (currentCommand == query) "" else it.inputText,
                isAiWorking = true,
                groupAiAnswer = null,
                groupAiQuestion = query,
                groupAiMode = mode,
                groupAiTasks = emptyList(),
                isSavingGroupAiTasks = false,
                groupAiTasksSaved = false,
                groupAiTaskSaveError = null,
                groupAiAnswerShared = false,
                groupEncryptionWarning = null
            )
        }
        if (currentCommand == query) clearDraft()
        val job = viewModelScope.launch {
            try {
                if (!groupAiGate.isCurrent(generation)) return@launch
                requireAiRequestCurrent(request)
                val liveToken = tokenManager.getToken().orEmpty()
                ApiService.groupAssistant(liveToken, query, contextMessages, mode, request.chatId).fold(
                    onSuccess = { response ->
                        if (!groupAiGate.isCurrent(generation)) return@fold
                        requireAiRequestCurrent(request)
                        _uiState.update {
                            it.copy(
                                groupAiAnswer = com.maodouchat.ai.AiPromptSafetyPolicy
                                    .annotateIfPrivilegedHallucination(
                                        response.answer.trim().take(4_000),
                                        text(R.string.chat_ai_privilege_hallucination_disclaimer)
                                    ),
                                groupAiMode = response.mode,
                                groupAiTasks = response.tasks.take(30).mapNotNull { task ->
                                    val title = task.title.trim().take(300).takeIf(String::isNotBlank)
                                        ?: return@mapNotNull null
                                    task.copy(
                                        title = title,
                                        owner = task.owner?.trim()?.take(100)?.takeIf(String::isNotBlank),
                                        dueText = task.dueText?.trim()?.take(120)?.takeIf(String::isNotBlank),
                                        dueAt = task.dueAt?.takeIf { dueAt -> dueAt > 0L }
                                    )
                                },
                                isAiWorking = false
                            )
                        }
                    },
                    onFailure = { error ->
                        if (!groupAiGate.isCurrent(generation)) return@fold
                        requireAiRequestCurrent(request)
                        _uiState.update {
                            it.copy(
                                inputText = if (it.inputText.isBlank()) "@AI $query" else it.inputText,
                                isAiWorking = false,
                                groupEncryptionWarning = error.message ?: text(R.string.chat_group_ai_failed)
                            )
                        }
                        scheduleDraftPersistence(_uiState.value.inputText)
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (groupAiGate.isCurrent(generation) && isAiRequestCurrent(request)) {
                    _uiState.update { it.copy(isAiWorking = false) }
                }
                throw error
            }
        }
        groupAiJob = job
        job.invokeOnCompletion {
            if (groupAiJob === job) groupAiJob = null
        }
    }

    internal fun ChatDetailViewModel.semanticSearch(query: String, candidateMessageIds: List<String>) {
        val candidateIdSet = candidateMessageIds.toHashSet()
        val state = _uiState.value
        val candidates = state.messages
            .asSequence()
            .filter { it.id in candidateIdSet }
            .mapNotNull { message ->
                val searchableText = message.semanticSearchText()
                if (searchableText.isBlank()) return@mapNotNull null
                val sender = if (message.senderId == currentUserId) {
                    "me"
                } else {
                    state.chat?.participants?.firstOrNull { it.id == message.senderId }?.displayName
                        ?: state.contact.displayName
                }
                AiSemanticSearchCandidate(
                    messageId = message.id,
                    sender = sender.take(80),
                    text = searchableText.take(700),
                    timestamp = message.timestamp
                )
            }
            .take(80)
            .toList()
        if (candidates.isEmpty()) {
            _uiState.update { it.copy(semanticSearchError = text(R.string.chat_semantic_search_no_context)) }
            return
        }
        val request = captureAiRequestSnapshot()
        if (request == null) {
            _uiState.update { it.copy(semanticSearchError = text(R.string.error_session_expired)) }
            return
        }
        semanticSearchJob?.cancel()
        val generation = semanticSearchGate.next()
        val job = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    semanticSearchResultIds = emptyList(),
                    semanticSearchQuery = query,
                    isSemanticSearching = true,
                    semanticSearchError = null
                )
            }
            try {
                requireAiRequestCurrent(request)
                val liveToken = tokenManager.getToken().orEmpty()
                ApiService.semanticSearch(liveToken, query, candidates, limit = 12, chatId = request.chatId).fold(
                    onSuccess = { response ->
                        requireAiRequestCurrent(request)
                        if (!semanticSearchGate.isCurrent(generation)) return@fold
                        val allowedIds = candidates.mapTo(hashSetOf()) { it.messageId }
                        val resultIds = response.matches
                            .asSequence()
                            .filter { it.messageId in allowedIds && it.score > 0.0 }
                            .map { it.messageId }
                            .distinct()
                            .take(12)
                            .toList()
                        _uiState.update {
                            it.copy(
                                semanticSearchResultIds = resultIds,
                                semanticSearchQuery = query,
                                isSemanticSearching = false,
                                semanticSearchError = null
                            )
                        }
                    },
                    onFailure = { error ->
                        requireAiRequestCurrent(request)
                        if (!semanticSearchGate.isCurrent(generation)) return@fold
                        _uiState.update {
                            it.copy(
                                semanticSearchResultIds = emptyList(),
                                semanticSearchQuery = query,
                                isSemanticSearching = false,
                                semanticSearchError = error.message ?: text(R.string.chat_semantic_search_failed)
                            )
                        }
                    }
                )
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (semanticSearchGate.isCurrent(generation) && isAiRequestCurrent(request)) {
                    _uiState.update {
                        it.copy(
                            isSemanticSearching = false,
                            semanticSearchError = error.message ?: text(R.string.chat_semantic_search_failed)
                        )
                    }
                }
            }
        }
        semanticSearchJob = job
        job.invokeOnCompletion { error ->
            if (semanticSearchJob === job) semanticSearchJob = null
            // Cancelled mid-request (clear / newer generation) must not leave spinner stuck.
            if (error is kotlinx.coroutines.CancellationException &&
                semanticSearchGate.isCurrent(generation) &&
                isAiRequestCurrent(request) &&
                _uiState.value.isSemanticSearching
            ) {
                _uiState.update { it.copy(isSemanticSearching = false) }
            }
        }
    }

    internal fun ChatDetailViewModel.buildGroupAiContextMessages(): List<AiContextMessage> {
        return buildPlainAiContextMessages(
            messages = _uiState.value.messages,
            senders = aiContextSenders(),
            limit = 30
        )
    }

    internal fun ChatDetailViewModel.inferGroupAiMode(query: String): String {
        val normalized = query.trim().lowercase()
        return when {
            normalized.startsWith("总结") || normalized.startsWith("概括") || normalized.startsWith("summary") || normalized.startsWith("summarize") -> "summary"
            normalized.startsWith("决策") || normalized.startsWith("决定") || normalized.startsWith("decisions") -> "decisions"
            normalized.startsWith("待办") || normalized.startsWith("任务") || normalized.startsWith("tasks") || normalized.startsWith("todo") -> "tasks"
            normalized.startsWith("时间线") || normalized.startsWith("时间轴") || normalized.startsWith("timeline") || normalized.startsWith("chronology") -> "timeline"
            normalized.startsWith("风险") || normalized.startsWith("隐患") || normalized.startsWith("risk") || normalized.startsWith("blocker") -> "risks"
            else -> "answer"
        }
    }

    internal fun ChatDetailViewModel.parseGroupAiCommand(value: String): String? {
        val trimmed = value.trim()
        if (!trimmed.startsWith("@AI", ignoreCase = true)) return null
        if (trimmed.length > 3 && !trimmed[3].isWhitespace()) return null
        return trimmed.drop(3).trim()
    }

    internal fun ChatDetailViewModel.rewriteDraft(mode: String, targetLanguage: String?) {
        val draft = _uiState.value.inputText.trim()
        if (draft.isBlank()) return
        val request = captureAiRequestSnapshot()
        if (request == null) {
            _uiState.update { it.copy(aiDraftStreamErrorCode = AiOperationError.CONTEXT_MISSING) }
            return
        }
        aiRewriteStreamJob?.cancel()
        val generation = aiRewriteGate.next()
        lastAiRewriteMode = mode
        lastAiRewriteTargetLanguage = targetLanguage
        val buffer = StringBuilder()
        var lastUiUpdateAt = 0L
        _uiState.update {
            it.copy(
                isAiWorking = true,
                aiDraftOriginal = draft,
                aiDraftPreview = "",
                isAiDraftStreaming = true,
                aiDraftStreamErrorCode = null,
                groupEncryptionWarning = null,
                aiSuggestions = emptyList(),
                aiReplyStreamErrorCode = null
            )
        }
        aiRewriteStreamJob = viewModelScope.launch {
            try {
                requireAiRequestCurrent(request)
                val liveToken = tokenManager.getToken().orEmpty()
                val styleHint = com.maodouchat.ai.AiWritingStylePolicy.rewriteStyleHint(
                    com.maodouchat.ai.AiWritingStylePreferences.snapshot(getApplication())
                )
                ApiService.streamRewriteMessage(
                    token = liveToken,
                    text = draft,
                    mode = mode,
                    targetLanguage = targetLanguage,
                    chatId = request.chatId,
                    styleHint = styleHint
                ) { event ->
                    if (!aiRewriteGate.isCurrent(generation) || event.type != "delta") return@streamRewriteMessage
                    if (!isAiRequestCurrent(request)) {
                        throw kotlinx.coroutines.CancellationException("ai_rewrite_session_changed")
                    }
                    val delta = event.text.orEmpty()
                    if (delta.isEmpty() || buffer.length >= 4_000) return@streamRewriteMessage
                    buffer.append(delta.take(4_000 - buffer.length))
                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdateAt >= 32L || '\n' in delta) {
                        lastUiUpdateAt = now
                        val preview = buffer.toString()
                        _uiState.update { state ->
                            if (aiRewriteGate.isCurrent(generation)) state.copy(aiDraftPreview = preview) else state
                        }
                    }
                }.fold(
                    onSuccess = {
                        if (!aiRewriteGate.isCurrent(generation) || !isAiRequestCurrent(request)) return@fold
                        val preview = buffer.toString().trim().take(4_000)
                        _uiState.update {
                            it.copy(
                                aiDraftPreview = preview,
                                isAiDraftStreaming = false,
                                isAiWorking = false,
                                aiDraftStreamErrorCode = if (preview.isBlank()) AiOperationError.EMPTY_RESULT else null
                            )
                        }
                    },
                    onFailure = { error ->
                        if (!aiRewriteGate.isCurrent(generation) || !isAiRequestCurrent(request)) return@fold
                        _uiState.update {
                            it.copy(
                                aiDraftPreview = buffer.toString().trim().take(4_000),
                                isAiDraftStreaming = false,
                                isAiWorking = false,
                                aiDraftStreamErrorCode = aiOperationErrorCode(error)
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (aiRewriteGate.isCurrent(generation) && isAiRequestCurrent(request)) {
                    _uiState.update {
                        it.copy(isAiDraftStreaming = false, isAiWorking = false)
                    }
                }
                throw error
            }
        }
    }

    internal fun ChatDetailViewModel.generateAiSuggestions(tone: String = "friendly") {
        val contextMessages = buildAiContextMessages(limit = 16)
        if (contextMessages.isEmpty()) return
        val request = captureAiRequestSnapshot()
        if (request == null) {
            _uiState.update { it.copy(aiReplyStreamErrorCode = AiOperationError.CONTEXT_MISSING) }
            return
        }
        aiReplyStreamJob?.cancel()
        val generation = aiReplyGate.next()
        val safeTone = when (tone.trim().lowercase()) {
            "natural", "friendly", "formal", "concise", "warm", "humorous", "direct", "empathetic", "encouraging" -> tone.trim().lowercase()
            else -> "friendly"
        }
        _uiState.update {
            it.copy(
                isAiWorking = true,
                isAiReplyStreaming = true,
                aiReplyStreamErrorCode = null,
                aiSuggestions = emptyList(),
                groupEncryptionWarning = null
            )
        }
        aiReplyStreamJob = viewModelScope.launch {
            try {
                requireAiRequestCurrent(request)
                val liveToken = tokenManager.getToken().orEmpty()
                ApiService.streamSuggestedReplies(liveToken, contextMessages, tone = safeTone, count = 4, chatId = request.chatId) { event ->
                    if (!aiReplyGate.isCurrent(generation) || event.type != "reply") return@streamSuggestedReplies
                    if (!isAiRequestCurrent(request)) {
                        throw kotlinx.coroutines.CancellationException("ai_reply_session_changed")
                    }
                    val reply = event.text?.trim()?.take(500).orEmpty()
                    if (reply.isBlank()) return@streamSuggestedReplies
                    _uiState.update { state ->
                        if (!aiReplyGate.isCurrent(generation)) state
                        else state.copy(aiSuggestions = (state.aiSuggestions + reply).distinct().take(4))
                    }
                }.fold(
                    onSuccess = {
                        if (!aiReplyGate.isCurrent(generation) || !isAiRequestCurrent(request)) return@fold
                        _uiState.update {
                            it.copy(
                                isAiReplyStreaming = false,
                                isAiWorking = false,
                                aiReplyStreamErrorCode = if (it.aiSuggestions.isEmpty()) AiOperationError.EMPTY_RESULT else null
                            )
                        }
                    },
                    onFailure = { error ->
                        if (!aiReplyGate.isCurrent(generation) || !isAiRequestCurrent(request)) return@fold
                        val offline = buildOfflineAiSuggestions(contextMessages, safeTone)
                        _uiState.update {
                            it.copy(
                                isAiReplyStreaming = false,
                                isAiWorking = false,
                                aiSuggestions = offline.ifEmpty { it.aiSuggestions },
                                aiReplyStreamErrorCode = if (offline.isNotEmpty()) null else aiOperationErrorCode(error),
                                groupEncryptionWarning = if (offline.isNotEmpty()) {
                                    text(R.string.ai_offline_suggestions_hint)
                                } else it.groupEncryptionWarning
                            )
                        }
                    }
                )
                // Empty upstream result -> offline heuristic fallback
                if (aiReplyGate.isCurrent(generation) &&
                    isAiRequestCurrent(request) &&
                    _uiState.value.aiSuggestions.isEmpty() &&
                    _uiState.value.aiReplyStreamErrorCode == AiOperationError.EMPTY_RESULT
                ) {
                    val offline = buildOfflineAiSuggestions(contextMessages, safeTone)
                    if (offline.isNotEmpty()) {
                        _uiState.update {
                            it.copy(
                                aiSuggestions = offline,
                                aiReplyStreamErrorCode = null,
                                groupEncryptionWarning = text(R.string.ai_offline_suggestions_hint)
                            )
                        }
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (aiReplyGate.isCurrent(generation) && isAiRequestCurrent(request)) {
                    _uiState.update {
                        it.copy(isAiReplyStreaming = false, isAiWorking = false)
                    }
                }
                throw error
            }
        }
    }

    /** Local, privacy-preserving reply chips when cloud AI is unavailable. */
    private fun ChatDetailViewModel.buildOfflineAiSuggestions(
        contextMessages: List<com.maodouchat.network.AiContextMessage>,
        tone: String
    ): List<String> {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.OFFLINE_AI)) return emptyList()
        val texts = contextMessages.map { it.text.trim() }.filter { it.isNotBlank() }
        if (texts.isEmpty()) return emptyList()
        val seed = texts.last().take(120)
        val recent = texts.takeLast(3).joinToString(" ").take(240)
        val lower = (seed + " " + recent).lowercase()
        val base = when {
            "?" in seed || "？" in seed || lower.startsWith("why") || lower.startsWith("how") ||
                "吗" in seed || "么" in seed || "呢" in seed -> listOf(
                "好问题，我的想法是……",
                "可以，稍等我整理一下再回你。",
                "我更倾向这个方向，你觉得呢？"
            )
            offlineHas(lower, seed, listOf("谢谢", "thanks", "thank")) -> listOf(
                "不客气～",
                "应该的，有需要再叫我。",
                "小事一桩。"
            )
            offlineHas(lower, seed, listOf("你好", "hello", "hi ", "在吗", "在不在")) -> listOf(
                "在的，怎么啦？",
                "嗨，刚看到～",
                "在呢，说吧。"
            )
            offlineHas(lower, seed, listOf("约", "见面", "吃饭", "电影")) -> listOf(
                "时间地点你定，我配合～",
                "可以啊，周末怎么样？",
                "我想去，细节再敲定。"
            )
            offlineHas(lower, seed, listOf("难过", "伤心", "累", "压力")) -> listOf(
                "我在这儿听你说。",
                "辛苦了，先歇一会儿吧。",
                "需要的话我陪你聊聊。"
            )
                        offlineHas(lower, seed, listOf("ok", "okay", "good night", "gn", "晚安", "好的", "行")) -> listOf(
                "好的，收到。",
                "嗯嗯，晚点聊。",
                "Alright, talk soon."
            )
            offlineHas(lower, seed, listOf("sorry", "抱歉", "不好意思", "对不起")) -> listOf(
                "没关系。",
                "理解你，没事的。",
                "It's okay, no worries."
            )
            offlineHas(lower, seed, listOf("love", "喜欢", "爱你", "么么")) -> listOf(
                "我也是～",
                "收到满满的喜欢。",
                "同样的感觉。"
            )
                        offlineHas(lower, seed, listOf("meeting", "会议", "开会", "sync")) -> listOf(
                "好的，我改一下时间。",
                "议程我稍后发你。",
                "Can we do a short call?"
            )
            offlineHas(lower, seed, listOf("price", "多少钱", "费用", "报价")) -> listOf(
                "我整理一版报价给你。",
                "方便说下预算范围吗？",
                "Let me send options."
            )
            offlineHas(lower, seed, listOf("photo", "图片", "照片", "看看")) -> listOf(
                "发我看看～",
                "收到，我仔细看下。",
                "Looks good!"
            )
            offlineHas(lower, seed, listOf("code", "bug", "报错", "崩溃", "error")) -> listOf(
                "日志发我一段。",
                "我这边复现一下。",
                "Might be a race; checking."
            )
            offlineHas(lower, seed, listOf("weather", "天气", "下雨", "温度")) -> listOf(
                "记得看下天气预报再出门～",
                "要不要改成室内活动？",
                "我这边帮你记着关注天气变化。"
            )
            offlineHas(lower, seed, listOf("deadline", "截止", "ddl", "明天交")) -> listOf(
                "截止日期我记下了，要不要拆成待办？",
                "先列三点关键路径，我陪你盯进度。",
                "需要我帮你写个简短提醒吗？"
            )
            offlineHas(lower, seed, listOf("travel", "出差", "高铁", "飞机", "酒店")) -> listOf(
                "行程我可以帮你整理成清单。",
                "要不要同步一下出发/到达时间？",
                "路上注意安全，到了报个平安～"
            )
            offlineHas(lower, seed, listOf("health", "感冒", "生病", "医院", "吃药")) -> listOf(
                "多休息喝温水，严重就去看医生。",
                "需要我帮你请假话术吗？",
                "好好照顾自己，别硬撑。"
            )
            offlineHas(lower, seed, listOf("weekend", "周末", "假期", "vacation", "holiday")) -> listOf(
                "周末有什么安排？",
                "好好休息，周一见。",
                "Any fun plans this weekend?"
            )
            offlineHas(lower, seed, listOf("congrats", "恭喜", "祝贺", "庆祝", "offer")) -> listOf(
                "太棒了，恭喜你！",
                "值得好好庆祝一下。",
                "Congrats — well earned!"
            )
            offlineHas(lower, seed, listOf("traffic", "堵车", "迟到", "晚到", "delay")) -> listOf(
                "注意安全，到了说一声。",
                "没关系，我可以等你。",
                "Safe travels, take your time."
            )
            offlineHas(lower, seed, listOf("food", "吃饭", "外卖", "餐厅", "lunch", "dinner")) -> listOf(
                "要一起点外卖吗？",
                "我可以帮你列几个选项。",
                "I'm hungry too — any preference?"
            )
            offlineHas(lower, seed, listOf("game", "游戏", "开黑", "上分", "match")) -> listOf(
                "现在开一局？",
                "我准备好了，你定模式。",
                "Queue up, I'm in."
            )
            offlineHas(lower, seed, listOf("movie", "电影", "剧", "追剧", "netflix")) -> listOf(
                "有推荐的片单吗？",
                "今晚一起看？",
                "Send the title, I'll check."
            )
            offlineHas(lower, seed, listOf("work", "加班", " ent", "项目", "deadline")) -> listOf(
                "进度我记下了，需要帮忙拆任务吗？",
                "先聚焦最关键的一件事。",
                "Want a quick status checklist?"
            )
            offlineHas(lower, seed, listOf("money", "转账", "付款", "账单", "pay")) -> listOf(
                "金额确认后我再操作。",
                "发我账单明细～",
                "I'll confirm and get back."
            )
            offlineHas(lower, seed, listOf("birthday", "生日快乐", "过生")) -> listOf(
                "生日快乐！今天过得开心点。",
                "要不要一起安排个小庆祝？",
                "送你一个虚拟蛋糕"
            )
            offlineHas(lower, seed, listOf("study", "学习", "考试", "exam", "homework")) -> listOf(
                "要不要一起复盘一下重点？",
                "先休息五分钟再继续。",
                "I can quiz you on the hard parts."
            )
            offlineHas(lower, seed, listOf("sport", "跑步", "健身", "gym", "workout")) -> listOf(
                "今天练哪一块？",
                "加油，注意拉伸。",
                "Send me your PR, I want to cheer."
            )
            offlineHas(lower, seed, listOf("music", "歌", "playlist", "演唱会")) -> listOf(
                "发我歌单听听。",
                "这首循环了好几遍。",
                "Any new recommendations?"
            )
            offlineHas(lower, seed, listOf("pet", "猫", "狗", "铲屎", "puppy", "kitty")) -> listOf(
                "毛孩子今天乖不乖？",
                "求吸猫/吸狗现场。",
                "Pet tax please "
            )
            offlineHas(lower, seed, listOf("secret", "密聊", "加密", "e2ee")) -> listOf(
                "敏感内容我们用密聊说。",
                "记得开阅后即焚。",
                "I'll keep this private."
            )
            offlineHas(lower, seed, listOf("sleep", "失眠", "困", "熬夜", "insomnia")) -> listOf(
                "早点休息，明天再说。",
                "我先不打扰你了。",
                "Sleep well — talk tomorrow."
            )
            offlineHas(lower, seed, listOf("coffee", "咖啡", "奶茶", "tea")) -> listOf(
                "来一杯提神？",
                "我请你喝。",
                "Coffee or tea?"
            )
            offlineHas(lower, seed, listOf("rain", "下雪", "台风", "storm")) -> listOf(
                "出门记得带伞。",
                "注意安全。",
                "Stay dry out there."
            )
            offlineHas(lower, seed, listOf("meeting cancel", "取消", "改期", "reschedule")) -> listOf(
                "那我们另约时间。",
                "收到，我改日历了。",
                "No problem — propose a new slot."
            )
            offlineHas(lower, seed, listOf("flight", "航班", "高铁", "train", "airport")) -> listOf(
                "一路顺风，落地报平安。",
                "需要我帮你看时刻表吗？",
                "Safe travels — ping me when you land."
            )
            offlineHas(lower, seed, listOf("wifi", "网络", "断网", "lag", "卡顿")) -> listOf(
                "可能是网络波动，稍后再试。",
                "我这边也有点卡。",
                "Try switching networks?"
            )
            offlineHas(lower, seed, listOf("gift", "礼物", "惊喜", "present")) -> listOf(
                "要不要一起挑个礼物？",
                "保密，别剧透～",
                "I have an idea — call me."
            )
            offlineHas(lower, seed, listOf("interview", "面试", "offer", "hr")) -> listOf(
                "祝你顺利，稳住发挥。",
                "需要我帮你过一遍常见问题吗？",
                "You've got this — knock them out."
            )
            offlineHas(lower, seed, listOf("battery", "没电", "充电", "low battery")) -> listOf(
                "快没电了，我先去充电。",
                "回头再聊～",
                "Powering up — brb."
            )
            offlineHas(lower, seed, listOf("map", "迷路", "导航", "lost", "directions")) -> listOf(
                "发我定位，我帮你看。",
                "别急，先找个地标。",
                "Share your pin, I'll guide you."
            )
            offlineHas(lower, seed, listOf("package", "快递", "外卖到了", "delivery")) -> listOf(
                "收到了说一声。",
                "我下楼拿。",
                "I'll grab it."
            )
            offlineHas(lower, seed, listOf("//", "code review", "pr ", "merge")) -> listOf(
                "我晚点看你的 PR。",
                "有冲突先 rebase 一下。",
                "LGTM with nits — shipping."
            )
            offlineHas(lower, seed, listOf("cook", "做饭", "菜谱", "recipe")) -> listOf(
                "今晚想吃什么？",
                "发我菜谱链接～",
                "I can help plan the menu."
            )
            offlineHas(lower, seed, listOf("plant", "浇花", "绿植", "garden")) -> listOf(
                "别忘了浇水。",
                "新芽发了吗？",
                "Plant tax photos please."
            )
            offlineHas(lower, seed, listOf("book", "读书", "小说", "reading")) -> listOf(
                "最近在看什么？",
                "读完安利我。",
                "Drop the title — adding to my list."
            )
            offlineHas(lower, seed, listOf("gym fail", "没去练", "偷懒", "rest day")) -> listOf(
                "休息也是训练的一部分。",
                "明天补上就好。",
                "Rest day accepted."
            )
            offlineHas(lower, seed, listOf("password", "密码", "2fa", "验证码", "totp")) -> listOf(
                "别在群里发验证码。",
                "建议开 TOTP 两步验证。",
                "Reset via secure channel only."
            )
            offlineHas(lower, seed, listOf("screenshot", "截图", "录屏", "screen record")) -> listOf(
                "密聊请勿截图。",
                "有盲水印可追溯。",
                "Use view-once if sensitive."
            )
            offlineHas(lower, seed, listOf("budget", "预算", "省钱", "理财")) -> listOf(
                "我们列个简单预算表。",
                "先区分必要与可选开支。",
                "Want a 3-line budget?"
            )
            offlineHas(lower, seed, listOf("doctor", "医院", "挂号", "clinic")) -> listOf(
                "早去排队，记得带证件。",
                "需要我陪你吗？",
                "Feel better soon."
            )
            offlineHas(lower, seed, listOf("parking", "停车", "挪车", "garage")) -> listOf(
                "我马上挪一下。",
                "发我位置。",
                "On my way to move it."
            )
            offlineHas(lower, seed, listOf("vpn", "代理", "翻墙", "proxy")) -> listOf(
                "注意账号安全，别分享节点。",
                "优先用官方通道。",
                "Keep credentials private."
            )
            offlineHas(lower, seed, listOf("backup", "备份", "导出聊天", "export chat")) -> listOf(
                "密聊内容不建议明文导出。",
                "可用加密备份方案。",
                "Prefer encrypted backups only."
            )
            offlineHas(lower, seed, listOf("pin message", "unpin message", "message pin")) -> listOf(
                "Pinned for the group.",
                "Unpinned — no longer sticky.",
                "Pin the important update."
            )
            offlineHas(lower, seed, listOf("revoke message", "delete message", "unsend")) -> listOf(
                "Revoked — pretend you didn't see it.",
                "Deleted on my side.",
                "I'll unsend that."
            )
            offlineHas(lower, seed, listOf("pin the mood", "revoke rush", "secret signal")) -> listOf(
                "Pin the mood for today.",
                "Revoke rush starts now.",
                "Secret signal received."
            )
            offlineHas(lower, seed, listOf("doc hunt", "meaning race", "insight sprint", "ai file", "semantic search", "analyze file")) -> listOf(
                "Doc hunt - find the clause.",
                "Meaning race - semantic win.",
                "Insight sprint - one key takeaway.",
                "AI file analysis is admin-gated.",
                "Semantic search can be limited."
            )
            offlineHas(lower, seed, listOf("pixel quest", "assist circle", "decision dash", "ai analyze", "group assistant", "image analyze")) -> listOf(
                "Pixel quest - find the clue in the photo.",
                "Assist circle - group AI recap.",
                "Decision dash - pick next steps.",
                "AI image analysis is admin-gated.",
                "Group assistant can be limited."
            )
            offlineHas(lower, seed, listOf("suggest circle", "voice race", "reply sprint", "ai suggest", "ai transcribe", "suggest replies")) -> listOf(
                "Suggest circle - share a quick reply idea.",
                "Voice race - short clear note.",
                "Reply sprint - three options fast.",
                "AI suggest replies is admin-gated.",
                "AI transcribe can be limited."
            )
            offlineHas(lower, seed, listOf("photo race", "clip dash", "frame hunt", "summary circle", "rewrite relay", "prompt sprint", "image send", "video send", "ai summary", "ai rewrite")) -> listOf(
                "Photo race - first clear snap.",
                "Clip dash - short video win.",
                "Frame hunt - find the detail.",
                "Summary circle - one-line recap.",
                "Rewrite relay - polish the draft.",
                "Prompt sprint - ask better."
            )
            offlineHas(lower, seed, listOf("pin drop", "file relay", "map dash", "vault lock", "watermark hunt", "secure sprint", "secret chat", "screen secure")) -> listOf(
                "Pin drop - share a static pin.",
                "File relay - pass the document.",
                "Map dash - race the route.",
                "Vault lock - secret chat on.",
                "Watermark hunt - find the mark.",
                "Secure sprint - FLAG_SECURE active."
            )
            offlineHas(lower, seed, listOf("spoiler race", "blur battle", "download dash", "spoiler media", "auto download")) -> listOf(
                "Spoiler race - no peeking.",
                "Blur battle - guess the shot.",
                "Download dash - save on wifi.",
                "Spoiler media is admin-gated.",
                "Auto-download can be limited."
            )
            offlineHas(lower, seed, listOf("qr quest", "contact swap", "scan sprint", "qr code", "contact card")) -> listOf(
                "QR quest - frame and scan.",
                "Contact swap - share cards carefully.",
                "Scan sprint - steady hands.",
                "QR codes stay optional.",
                "Contact cards are admin-gated."
            )
            offlineHas(lower, seed, listOf("nudge dash", "code check", "trust sprint", "nudge", "safety code")) -> listOf(
                "Nudge dash - double-tap race.",
                "Code check - compare digits offline.",
                "Trust sprint - verify safety code.",
                "Nudge is a light poke, not a call.",
                "Safety codes stay on-device."
            )
            offlineHas(lower, seed, listOf("invite race", "mention mayhem", "link hunt", "group invite", "mentions")) -> listOf(
                "Invite race starts now.",
                "Mention mayhem - tag carefully.",
                "Link hunt - find the clue.",
                "Invite link is ready.",
                "Mentions stay private in E2EE."
            )
            offlineHas(lower, seed, listOf("idea relay", "tempo tap", "translate relay", "drafts", "ai translate")) -> listOf(
                "Idea relay - pass one idea.",
                "Tempo tap - keep the beat.",
                "Translate relay - next language.",
                "Draft saved on this device.",
                "AI translate is ready."
            )
            offlineHas(lower, seed, listOf("mood meter", "focus sprint", "gratitude round", "polls", "app lock")) -> listOf(
                "Rate the mood meter 1-10.",
                "Focus sprint - set a timer.",
                "Gratitude round: one win.",
                "Quick poll is ready.",
                "App lock keeps the session private."
            )
            offlineHas(lower, seed, listOf("chat lock", "lock chat", "pin lock")) -> listOf(
                "Chat lock is on — enter PIN.",
                "Unlock when you're ready.",
                "Keep the lock PIN private."
            )
            offlineHas(lower, seed, listOf("edit message", "message edit", "typo fix")) -> listOf(
                "Edited — fixed the typo.",
                "Edit window is short, act fast.",
                "I'll edit that message."
            )
            offlineHas(lower, seed, listOf("code breaker", "silly law", "emoji math")) -> listOf(
                "Code breaker — four digits.",
                "New silly law for the group.",
                "Emoji math — solve it."
            )
            offlineHas(lower, seed, listOf("mute chat", "unmute", "notifications off")) -> listOf(
                "Muted this chat for focus.",
                "Unmute when free.",
                "Silence is intentional."
            )
            offlineHas(lower, seed, listOf("disappear", "disappearing", "auto delete", "阅后即焚")) -> listOf(
                "Timer is set — messages will vanish.",
                "Use a short timer for sensitive stuff.",
                "Disappearing messages keep history light."
            )
            offlineHas(lower, seed, listOf("impulse draw", "word scramble", "reaction duel")) -> listOf(
                "Impulse draw — lucky you?",
                "Unscramble this word.",
                "Reaction duel — pick a side."
            )
            offlineHas(lower, seed, listOf("pin chat", "pinned", "unpin")) -> listOf(
                "Pinned so I don't lose it.",
                "Unpin when done.",
                "Pin the important thread."
            )
            offlineHas(lower, seed, listOf("marked unread", "mark unread", "unread later")) -> listOf(
                "Marked unread for later.",
                "I'll clear it when I finish.",
                "Unread badge is intentional."
            )
            offlineHas(lower, seed, listOf("mirror echo", "sync clap", "fact or fiction")) -> listOf(
                "Mirror echo — reverse me.",
                "Sync clap on three.",
                "Fact or fiction — guess!"
            )
            offlineHas(lower, seed, listOf("archive", "archived", "inbox")) -> listOf(
                "Archived — ping if urgent.",
                "I'll unarchive later.",
                "Inbox zero-ish after archive."
            )
            offlineHas(lower, seed, listOf("nearby", "around me", "local people")) -> listOf(
                "Nearby is optional privacy-wise.",
                "Turn radius down if crowded.",
                "Sharing location only when needed."
            )
            offlineHas(lower, seed, listOf("debate", "emoji story", "quick poll")) -> listOf(
                "Debate flash — pick a side.",
                "Emoji story round!",
                "Quick poll — vote now."
            )
            offlineHas(lower, seed, listOf("moments", "posts", "timeline")) -> listOf(
                "Check my latest post when free.",
                "Moments can wait — chatting first.",
                "I just shared something on Moments."
            )
            offlineHas(lower, seed, listOf("block", "report", "spam", "harass")) -> listOf(
                "You can block or report if needed.",
                "Safety first — don't tolerate abuse.",
                "I can help you report this."
            )
            offlineHas(lower, seed, listOf("alphabet", "silent movie", "color word")) -> listOf(
                "Alphabet race — your turn.",
                "Silent movie round next.",
                "Color-word challenge accepted."
            )
            offlineHas(lower, seed, listOf("sticker", "表情包", "emoji pack")) -> listOf(
                "发一个合适的贴纸？",
                "这个表情包绝了。",
                "Sticker energy."
            )
            offlineHas(lower, seed, listOf("silent", "无声", "免打扰", "dnd")) -> listOf(
                "我用无声发送，不吵你。",
                "先免打扰，晚点聊。",
                "Sending silently."
            )
            offlineHas(lower, seed, listOf("watermark", "盲水印", "取证")) -> listOf(
                "截图会有盲水印痕迹。",
                "后台可提取水印信息。",
                "Forensics-ready."
            )
            offlineHas(lower, seed, listOf("打电话", "call me", "视频通话", "voice call", "facetime")) -> listOf(
                "我现在方便接电话。",
                "改文字聊也可以。",
                "Want a quick call?"
            )
            offlineHas(lower, seed, listOf("定时", "稍后发", "schedule", "remind me later")) -> listOf(
                "我设个定时消息。",
                "到点我提醒你。",
                "I'll schedule it."
            )
            offlineHas(lower, seed, listOf("群公告", "announcement", "置顶", "pin this")) -> listOf(
                "建议置顶关键信息。",
                "我来发一版群公告草稿。",
                "Pin the summary?"
            )
            offlineHas(lower, seed, listOf("阅后即焚", "view once", "看完即焚", "viewonce")) -> listOf(
                "敏感图用阅后即焚发。",
                "看完就没了，注意隐私。",
                "Sending as view-once."
            )
            offlineHas(lower, seed, listOf("实时位置", "live location", "共享位置", "share location")) -> listOf(
                "我开了实时位置，到了关。",
                "只共享一会儿。",
                "Sharing live location briefly."
            )
            offlineHas(lower, seed, listOf("机器人", "bot api", "webhook", "开发者")) -> listOf(
                "可以自助接入机器人。",
                "Webhook 记得验签。",
                "Check the bot developer docs."
            )
            offlineHas(lower, seed, listOf("markdown", "md 格式", "代码块", "fenced")) -> listOf(
                "支持 Markdown 渲染，注意管理员开关。",
                "代码块用三个反引号包起来。",
                "Markdown looks great in Maodouchat."
            )
            offlineHas(lower, seed, listOf("正在输入", "typing", "输入中", "对方在打字")) -> listOf(
                "对方输入状态可关，保护隐私。",
                "我看到你在打字了。",
                "Typing indicators are optional."
            )
            offlineHas(lower, seed, listOf("禁忌词", "taboo", "闪电回合", "两词故事")) -> listOf(
                "来局禁忌词描述吧！",
                "闪电回合，十秒开抢。",
                "Two-word story time?"
            )
            offlineHas(lower, seed, listOf("已读", "read receipt", "双勾", "seen")) -> listOf(
                "已读回执可在后台关闭。",
                "我这边已读了。",
                "Read receipts are privacy-gated."
            )
            offlineHas(lower, seed, listOf("在线", "presence", "last seen", "最后在线")) -> listOf(
                "在线状态也可关闭，更私密。",
                "我现在在线。",
                "Presence is optional."
            )
            offlineHas(lower, seed, listOf("悄悄话", "whisper", "倒计时抢答", "表情对决")) -> listOf(
                "来局悄悄话挑战！",
                "倒计时抢答开始。",
                "Emoji duel — pick a side!"
            )
            offlineHas(lower, seed, listOf("星标", "star message", "收藏消息", "bookmark")) -> listOf(
                "重要消息可以星标。",
                "星标列表稍后一起看。",
                "I'll star that for later."
            )
            offlineHas(lower, seed, listOf("导出聊天", "export chat", "备份聊天", "export history")) -> listOf(
                "导出前注意密聊限制。",
                "管理员可关闭导出。",
                "Export is privacy-gated."
            )
            offlineHas(lower, seed, listOf("地理猜猜", "geo guess", "表情记忆", "极速报菜名")) -> listOf(
                "来局地理猜猜！",
                "表情记忆，看谁记得牢。",
                "Rapid fire — go!"
            )
            offlineHas(lower, seed, listOf("转发", "forward", "转给", "share message")) -> listOf(
                "转发注意密聊限制。",
                "管理员可关闭转发。",
                "Forwarding is privacy-gated."
            )
            offlineHas(lower, seed, listOf("全局搜索", "global search", "搜聊天记录", "search chats")) -> listOf(
                "全局搜索可在后台关闭。",
                "我帮你关键词定位。",
                "Search is optional."
            )
            offlineHas(lower, seed, listOf("一词接龙", "极速心算", "故事种子", "one word")) -> listOf(
                "来局一词接龙！",
                "极速心算，看谁快。",
                "Story seed — your line!"
            )
            offlineHas(lower, seed, listOf("加好友", "好友申请", "friend request", "加个好友")) -> listOf(
                "我发了好友申请。",
                "好友申请可后台关闭。",
                "Friend request sent."
            )
            offlineHas(lower, seed, listOf("文件夹", "会话分组", "chat folder", "整理会话")) -> listOf(
                "可以用文件夹整理会话。",
                "文件夹功能可后台关闭。",
                "Folders keep chats tidy."
            )
            offlineHas(lower, seed, listOf("纯表情", "盲抽", "二选一加强", "emoji only")) -> listOf(
                "来局纯表情挑战！",
                "盲抽表情，猜猜是啥。",
                "Would you rather — round 2!"
            )
            else -> listOf(
                "收到，我晚点仔细回你。",
                "明白了。",
                "嗯嗯，继续说。"
            )
        }
        val toned = when (tone) {
            "formal" -> base.map {
                it.replace("～", "。").replace("嗯嗯，", "好的，")
            }
            "concise" -> base.map { it.take(14) }
            "humorous" -> base.map { "$it :)" }
            "warm" -> base.map { if (it.endsWith("。") || it.endsWith("～")) it else "$it～" }
            else -> base
        }
        return toned.distinct().take(4)
    }

    private fun offlineHas(lower: String, seed: String, keys: List<String>): Boolean =
        keys.any { key -> key.lowercase() in lower || key in seed }

fun ChatDetailViewModel.cancelAiDraftStream() {
        aiRewriteGate.invalidate()
        aiRewriteStreamJob?.cancel()
        aiRewriteStreamJob = null
        _uiState.update { state ->
            if (state.aiDraftPreview.isBlank()) {
                state.copy(
                    aiDraftOriginal = null,
                    aiDraftPreview = "",
                    isAiDraftStreaming = false,
                    aiDraftStreamErrorCode = null,
                    isAiWorking = false
                )
            } else {
                state.copy(
                    isAiDraftStreaming = false,
                    aiDraftStreamErrorCode = "CANCELLED",
                    isAiWorking = false
                )
            }
        }
    }

    fun ChatDetailViewModel.discardAiDraftPreview() {
        val wasStreaming = _uiState.value.isAiDraftStreaming
        aiRewriteGate.invalidate()
        aiRewriteStreamJob?.cancel()
        aiRewriteStreamJob = null
        _uiState.update {
            it.copy(
                aiDraftOriginal = null,
                aiDraftPreview = "",
                isAiDraftStreaming = false,
                aiDraftStreamErrorCode = null,
                isAiWorking = if (wasStreaming) false else it.isAiWorking
            )
        }
    }

    fun ChatDetailViewModel.applyAiDraftPreview() {
        val preview = _uiState.value.aiDraftPreview.trim().take(4_000)
        if (preview.isBlank()) return
        if (_uiState.value.isAiReplyStreaming) cancelAiReplyStream(clearSuggestions = true)
        val wasStreaming = _uiState.value.isAiDraftStreaming
        aiRewriteGate.invalidate()
        aiRewriteStreamJob?.cancel()
        aiRewriteStreamJob = null
        hasUserEditedInput = true
        _uiState.update {
            it.copy(
                inputText = preview,
                aiDraftOriginal = null,
                aiDraftPreview = "",
                isAiDraftStreaming = false,
                aiDraftStreamErrorCode = null,
                isAiWorking = if (wasStreaming) false else it.isAiWorking,
                groupEncryptionWarning = text(R.string.chat_ai_rewrite_success)
            )
        }
        scheduleDraftPersistence(preview)
    }

    fun ChatDetailViewModel.retryAiDraftStream() {
        _uiState.value.aiDraftOriginal?.takeIf(String::isNotBlank) ?: return
        requestAiRewrite(lastAiRewriteMode, lastAiRewriteTargetLanguage)
    }

    fun ChatDetailViewModel.cancelAiReplyStream(clearSuggestions: Boolean = false) {
        val wasStreaming = _uiState.value.isAiReplyStreaming
        aiReplyGate.invalidate()
        aiReplyStreamJob?.cancel()
        aiReplyStreamJob = null
        _uiState.update {
            it.copy(
                isAiReplyStreaming = false,
                isAiWorking = if (wasStreaming) false else it.isAiWorking,
                aiSuggestions = if (clearSuggestions) emptyList() else it.aiSuggestions,
                aiReplyStreamErrorCode = null
            )
        }
    }

    fun ChatDetailViewModel.retryAiReplyStream() = requestAiSuggestions(lastAiReplyTone)

    internal fun ChatDetailViewModel.summarizeMessages(
        scope: AiSummaryScope,
        frozenMessages: List<Message>,
        operationId: String? = null,
        style: String = "brief"
    ) {
        val candidates = frozenMessages
            .sortedBy(Message::timestamp)
            .takeLast(MAX_AI_SUMMARY_MESSAGES)
        val contextMessages = buildAiSummaryContextMessages(candidates)
        if (contextMessages.isEmpty()) {
            launchTrackedAiOperation(operationId) {
                failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
            }
            return
        }
        val safeStyle = when (style.trim().lowercase()) {
            "brief", "detailed", "decisions", "tasks", "timeline", "risks" -> style.trim().lowercase()
            else -> if (scope == AiSummaryScope.RECENT) "brief" else "detailed"
        }
        val first = candidates.first()
        val last = candidates.last()
        val request = captureAiRequestSnapshot()
        if (request == null) {
            launchTrackedAiOperation(operationId) {
                failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
            }
            return
        }
        manualSummaryJob?.cancel()
        val generation = manualSummaryGate.next()
        val cacheKey = "manual:${request.chatId}:${scope.name}:$safeStyle:${first.id}:${last.id}:${contextMessages.size}"
        val job = launchTrackedAiOperation(operationId, startImmediately = false) {
            if (!manualSummaryGate.isCurrent(generation)) return@launchTrackedAiOperation
            requireAiRequestCurrent(request)
            _uiState.update {
                it.copy(
                    isAiWorking = true,
                    groupEncryptionWarning = null,
                    aiSummary = null,
                    aiSummaryScope = scope,
                    aiSummaryMessageCount = contextMessages.size
                )
            }
            val cached = withContext(Dispatchers.IO) {
                val requestToken = tokenManager.getToken().orEmpty()
                aiSummarySyncRepo.pull(
                    requestToken,
                    request.userId,
                    liveToken = tokenManager::getToken,
                    liveUserId = tokenManager::getUserId
                )
                aiSummaryRepo.getSummary(cacheKey)
            }
            if (!manualSummaryGate.isCurrent(generation)) return@launchTrackedAiOperation
            requireAiRequestCurrent(request)
            if (cached != null) {
                if (!completeAiOperation(operationId)) return@launchTrackedAiOperation
                val displaySummary = AiPromptSafetyPolicy.annotateIfPrivilegedHallucination(
                    cached.summary,
                    text(R.string.chat_ai_privilege_hallucination_disclaimer)
                )
                _uiState.update {
                    it.copy(
                        aiSummary = displaySummary,
                        aiSummaryScope = scope,
                        aiSummaryMessageCount = cached.messageCount,
                        isAiWorking = false
                    )
                }
                viewModelScope.launch(Dispatchers.IO) {
                    aiSummarySyncRepo.push(
                        tokenManager.getToken().orEmpty(),
                        request.userId,
                        cached,
                        scope.name,
                        liveToken = tokenManager::getToken,
                        liveUserId = tokenManager::getUserId
                    )
                }
                return@launchTrackedAiOperation
            }
            requireAiRequestCurrent(request)
            val liveToken = tokenManager.getToken().orEmpty()
            ApiService.summarizeChat(liveToken, contextMessages, style = safeStyle, chatId = request.chatId).fold(
                onSuccess = { response ->
                    if (!manualSummaryGate.isCurrent(generation)) return@fold
                    requireAiRequestCurrent(request)
                    val generatedSummary = response.summary.take(3_000)
                    if (generatedSummary.isBlank()) {
                        failAiOperation(operationId, AiOperationError.EMPTY_RESULT)
                        _uiState.update {
                            it.copy(
                                isAiWorking = false,
                                aiSummaryScope = null,
                                aiSummaryMessageCount = 0,
                                groupEncryptionWarning = text(R.string.chat_ai_summary_failed)
                            )
                        }
                        return@fold
                    }
                    val saved = withContext(Dispatchers.IO) {
                        aiSummaryRepo.saveSummary(
                            cacheKey = cacheKey,
                            chatId = request.chatId,
                            startMessageId = first.id,
                            endMessageId = last.id,
                            messageCount = contextMessages.size,
                            summary = generatedSummary
                        )
                    }
                    if (!manualSummaryGate.isCurrent(generation)) return@fold
                    requireAiRequestCurrent(request)
                    if (!completeAiOperation(operationId)) return@fold
                    val displaySummary = com.maodouchat.ai.AiPromptSafetyPolicy
                        .annotateIfPrivilegedHallucination(
                            generatedSummary,
                            text(R.string.chat_ai_privilege_hallucination_disclaimer)
                        )
                    _uiState.update {
                        it.copy(
                            aiSummary = displaySummary,
                            isAiWorking = false
                        )
                    }
                    viewModelScope.launch(Dispatchers.IO) {
                        aiSummarySyncRepo.push(
                            tokenManager.getToken().orEmpty(),
                            request.userId,
                            saved,
                            scope.name,
                            liveToken = tokenManager::getToken,
                            liveUserId = tokenManager::getUserId
                        )
                    }
                },
                onFailure = { error ->
                    if (!manualSummaryGate.isCurrent(generation)) return@fold
                    requireAiRequestCurrent(request)
                    failAiOperation(operationId, aiOperationErrorCode(error))
                    _uiState.update {
                        it.copy(
                            isAiWorking = false,
                            aiSummaryScope = null,
                            aiSummaryMessageCount = 0,
                            groupEncryptionWarning = error.message ?: text(R.string.chat_ai_summary_failed)
                        )
                    }
                }
            )
        }
        manualSummaryJob = job
        job.invokeOnCompletion {
            if (manualSummaryJob === job) manualSummaryJob = null
        }
        job.start()
    }

    internal fun ChatDetailViewModel.summaryCandidates(
        scope: AiSummaryScope,
        searchResultIds: List<String>,
        sourceMessages: List<Message>
    ): List<Message> {
        val now = System.currentTimeMillis()
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val searchIds = searchResultIds.filter(String::isNotBlank).toHashSet()
        return sourceMessages
            .asSequence()
            .filterNot { it.parsedMeta().aiAssisted }
            .filter { it.aiSummaryContextText().isNotBlank() }
            .filter { message ->
                when (scope) {
                    AiSummaryScope.RECENT -> true
                    AiSummaryScope.TODAY -> message.timestamp >= todayStart
                    AiSummaryScope.SEVEN_DAYS -> message.timestamp >= now - SEVEN_DAYS_MS
                    AiSummaryScope.THIRTY_DAYS -> message.timestamp >= now - THIRTY_DAYS_MS
                    AiSummaryScope.SEARCH_RESULTS -> message.id in searchIds
                    AiSummaryScope.UNREAD -> false
                }
            }
            .sortedBy(Message::timestamp)
            .toList()
            .takeLast(if (scope == AiSummaryScope.RECENT) 24 else MAX_AI_SUMMARY_MESSAGES)
    }

    internal fun ChatDetailViewModel.buildAiSummaryContextMessages(messages: List<Message>): List<AiContextMessage> {
        return buildSummaryAiContextMessages(
            messages = messages,
            senders = aiContextSenders(),
            limit = MAX_AI_SUMMARY_MESSAGES
        )
    }

    internal fun ChatDetailViewModel.transcribeVoiceMessage(messageId: String, operationId: String? = null) {
        // 密聊会话禁止 AI 转写：解密明文不得送服务端 AI
        if (_uiState.value.isSecretChat == true) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_ai_blocked)) }
            return
        }
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_TRANSCRIBE)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.ai_transcribe_disabled)) }
            if (operationId != null) {
                launchTrackedAiOperation(operationId) {
                    failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
                }
            }
            return
        }
        if (_uiState.value.transcribingVoiceMessageIds.contains(messageId)) {
            launchTrackedAiOperation(operationId) {
                failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
            }
            return
        }
        val message = _uiState.value.messages.firstOrNull { it.id == messageId && it.type == MessageType.VOICE }
        if (message == null) {
            launchTrackedAiOperation(operationId) {
                failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
            }
            return
        }
        val request = captureAiRequestSnapshot()
        if (request == null) {
            launchTrackedAiOperation(operationId) { failAiOperation(operationId, AiOperationError.CONTEXT_MISSING) }
            return
        }
        launchTrackedAiOperation(operationId) {
            requireAiRequestCurrent(request)
            _uiState.update {
                it.copy(
                    transcribingVoiceMessageIds = it.transcribingVoiceMessageIds + messageId,
                    groupEncryptionWarning = null
                )
            }
            val prepared = withContext(Dispatchers.IO) {
                val localMessage = ensureLocalAttachment(message).getOrNull() ?: return@withContext null
                val voiceUri = runCatching { Uri.parse(localMessage.parsedContent()) }.getOrNull()
                    ?: return@withContext null
                val base64 = MediaCache.uriToRawBase64(getApplication(), voiceUri) ?: return@withContext null
                base64 to (localMessage.parsedMeta().fileMimeType ?: "audio/mp4")
            }
            requireAiRequestCurrent(request)
            if (prepared == null) {
                failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
                _uiState.update {
                    it.copy(
                        transcribingVoiceMessageIds = it.transcribingVoiceMessageIds - messageId,
                        groupEncryptionWarning = text(R.string.chat_voice_cache_missing)
                    )
                }
                return@launchTrackedAiOperation
            }
            requireAiRequestCurrent(request)
            val liveToken = tokenManager.getToken().orEmpty()
            ApiService.transcribeVoice(liveToken, prepared.first, mimeType = prepared.second, chatId = request.chatId).fold(
                onSuccess = { response ->
                    requireAiRequestCurrent(request)
                    val transcript = com.maodouchat.util.VoiceTranscriptPolicy.normalize(response.text)
                    if (transcript.isBlank()) {
                        failAiOperation(operationId, AiOperationError.EMPTY_RESULT)
                        _uiState.update {
                            it.copy(
                                transcribingVoiceMessageIds = it.transcribingVoiceMessageIds - messageId,
                                groupEncryptionWarning = text(R.string.chat_voice_transcript_empty)
                            )
                        }
                        return@fold
                    }
                    val current = _uiState.value.messages.firstOrNull { it.id == messageId } ?: message
                    val updatedMeta = current.parsedMeta().copy(voiceTranscript = transcript)
                    val updated = current.copy(
                        content = composeContentWithMeta(current.parsedContent(), updatedMeta),
                        meta = updatedMeta
                    )
                    if (!commitAiMessageResult(operationId, updated, request.userId, request.chatId)) return@fold
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map { if (it.id == messageId) updated else it },
                            transcribingVoiceMessageIds = state.transcribingVoiceMessageIds - messageId,
                            groupEncryptionWarning = text(R.string.chat_voice_transcribed)
                        )
                    }
                },
                onFailure = { error ->
                    requireAiRequestCurrent(request)
                    failAiOperation(operationId, aiOperationErrorCode(error))
                    _uiState.update {
                        it.copy(
                            transcribingVoiceMessageIds = it.transcribingVoiceMessageIds - messageId,
                            groupEncryptionWarning = error.message ?: text(R.string.chat_voice_transcribe_failed)
                        )
                    }
                }
            )
        }
    }

    internal fun ChatDetailViewModel.analyzeImageMessage(messageId: String, mode: AiImageAnalysisMode, operationId: String? = null) {
        // 密聊会话禁止 AI 图像分析：解密明文不得送服务端 AI
        if (_uiState.value.isSecretChat == true) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_ai_blocked)) }
            return
        }
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_ANALYZE_IMAGE)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.ai_analyze_image_disabled)) }
            if (operationId != null) {
                launchTrackedAiOperation(operationId) { failAiOperation(operationId, AiOperationError.CONTEXT_MISSING) }
            }
            return
        }
        if (_uiState.value.analyzingImageMessageIds.contains(messageId)) {
            launchTrackedAiOperation(operationId) {
                failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
            }
            return
        }
        val message = _uiState.value.messages.firstOrNull { it.id == messageId && it.type == MessageType.IMAGE }
        if (message == null) {
            launchTrackedAiOperation(operationId) {
                failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
            }
            return
        }
        val request = captureAiRequestSnapshot()
        if (request == null) {
            launchTrackedAiOperation(operationId) { failAiOperation(operationId, AiOperationError.CONTEXT_MISSING) }
            return
        }
        launchTrackedAiOperation(operationId) {
            requireAiRequestCurrent(request)
            _uiState.update {
                it.copy(
                    isAiWorking = true,
                    analyzingImageMessageIds = it.analyzingImageMessageIds + messageId,
                    aiImageAnalysisResult = null,
                    aiImageAnalysisMode = mode,
                    groupEncryptionWarning = null
                )
            }
            val imageBase64 = withContext(Dispatchers.IO) {
                try {
                    val localMessage = ensureLocalAttachment(message).getOrThrow()
                    ImagePicker.uriToBase64(
                        context = getApplication(),
                        uri = Uri.parse(localMessage.parsedContent()),
                        maxWidth = 1_024,
                        quality = 72
                    )
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            }
            requireAiRequestCurrent(request)
            if (imageBase64.isNullOrBlank()) {
                failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
                _uiState.update {
                    it.copy(
                        isAiWorking = false,
                        analyzingImageMessageIds = it.analyzingImageMessageIds - messageId,
                        aiImageAnalysisMode = null,
                        groupEncryptionWarning = text(R.string.chat_ai_image_unavailable)
                    )
                }
                return@launchTrackedAiOperation
            }
            requireAiRequestCurrent(request)
            val liveToken = tokenManager.getToken().orEmpty()
            ApiService.analyzeImage(liveToken, imageBase64, mode.wireValue, request.chatId).fold(
                onSuccess = { response ->
                    requireAiRequestCurrent(request)
                    if (response.mode != mode.wireValue || response.text.isBlank()) {
                        failAiOperation(operationId, AiOperationError.EMPTY_RESULT)
                        _uiState.update {
                            it.copy(
                                isAiWorking = false,
                                analyzingImageMessageIds = it.analyzingImageMessageIds - messageId,
                                aiImageAnalysisMode = null,
                                groupEncryptionWarning = text(R.string.chat_ai_image_failed)
                            )
                        }
                        return@fold
                    }
                    val resultText = response.text.trim().take(6_000)
                    val current = _uiState.value.messages.firstOrNull { it.id == messageId } ?: message
                    val currentMeta = current.parsedMeta()
                    val updatedMeta = currentMeta.copy(
                        aiImageAnalyses = currentMeta.aiImageAnalyses + (mode.wireValue to resultText),
                        preferredImageAnalysisMode = mode.wireValue
                    )
                    val updated = current.copy(
                        content = composeContentWithMeta(current.parsedContent(), updatedMeta),
                        meta = updatedMeta
                    )
                    if (!commitAiMessageResult(operationId, updated, request.userId, request.chatId)) return@fold
                    val displayImageResult = com.maodouchat.ai.AiPromptSafetyPolicy
                        .annotateIfPrivilegedHallucination(
                            resultText,
                            text(R.string.chat_ai_privilege_hallucination_disclaimer)
                        )
                    _uiState.update { state ->
                        state.copy(
                            isAiWorking = false,
                            analyzingImageMessageIds = state.analyzingImageMessageIds - messageId,
                            messages = state.messages.map { if (it.id == messageId) updated else it },
                            aiImageAnalysisResult = displayImageResult,
                            aiImageAnalysisMode = mode
                        )
                    }
                },
                onFailure = { error ->
                    requireAiRequestCurrent(request)
                    failAiOperation(operationId, aiOperationErrorCode(error))
                    _uiState.update {
                        it.copy(
                            isAiWorking = false,
                            analyzingImageMessageIds = it.analyzingImageMessageIds - messageId,
                            aiImageAnalysisMode = null,
                            groupEncryptionWarning = error.message ?: text(R.string.chat_ai_image_failed)
                        )
                    }
                }
            )
        }
    }

    internal fun ChatDetailViewModel.analyzeFileMessage(
        messageId: String,
        mode: AiFileAnalysisMode,
        question: String?,
        operationId: String? = null
    ) {
        // 密聊会话禁止 AI 文件分析：解密明文不得送服务端 AI
        if (_uiState.value.isSecretChat == true) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_ai_blocked)) }
            return
        }
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_ANALYZE_FILE)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.ai_analyze_file_disabled)) }
            if (operationId != null) {
                launchTrackedAiOperation(operationId) { failAiOperation(operationId, AiOperationError.CONTEXT_MISSING) }
            }
            return
        }
        if (_uiState.value.analyzingFileMessageIds.contains(messageId)) {
            launchTrackedAiOperation(operationId) {
                failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
            }
            return
        }
        val message = _uiState.value.messages.firstOrNull { it.id == messageId && it.type == MessageType.FILE }
        if (message == null) {
            launchTrackedAiOperation(operationId) {
                failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
            }
            return
        }
        if (mode == AiFileAnalysisMode.QUESTION && question.isNullOrBlank()) {
            launchTrackedAiOperation(operationId) {
                failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
            }
            return
        }
        val request = captureAiRequestSnapshot()
        if (request == null) {
            launchTrackedAiOperation(operationId) { failAiOperation(operationId, AiOperationError.CONTEXT_MISSING) }
            return
        }
        launchTrackedAiOperation(operationId) {
            requireAiRequestCurrent(request)
            _uiState.update {
                it.copy(
                    isAiWorking = true,
                    analyzingFileMessageIds = it.analyzingFileMessageIds + messageId,
                    aiFileAnalysisResult = null,
                    aiFileAnalysisMode = mode,
                    aiFileAnalysisName = message.parsedMeta().fileName,
                    groupEncryptionWarning = null
                )
            }
            val prepared = withContext(Dispatchers.IO) {
                val localMessage = ensureLocalAttachment(message).getOrNull() ?: return@withContext null
                val uri = runCatching { Uri.parse(localMessage.parsedContent()) }.getOrNull() ?: return@withContext null
                val base64 = MediaCache.uriToRawBase64(getApplication(), uri) ?: return@withContext null
                resolveAiFileInput(localMessage, base64)
            }
            requireAiRequestCurrent(request)
            if (prepared == null) {
                failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
                _uiState.update {
                    it.copy(
                        isAiWorking = false,
                        analyzingFileMessageIds = it.analyzingFileMessageIds - messageId,
                        aiFileAnalysisMode = null,
                        aiFileAnalysisName = null,
                        groupEncryptionWarning = text(R.string.chat_ai_file_unsupported)
                    )
                }
                return@launchTrackedAiOperation
            }
            requireAiRequestCurrent(request)
            val liveToken = tokenManager.getToken().orEmpty()
            ApiService.analyzeFile(
                token = liveToken,
                fileBase64 = prepared.base64,
                fileName = prepared.fileName,
                mimeType = prepared.mimeType,
                mode = mode.wireValue,
                question = question,
                chatId = request.chatId
            ).fold(
                onSuccess = { response ->
                    requireAiRequestCurrent(request)
                    if (response.mode != mode.wireValue || response.text.isBlank()) {
                        failAiOperation(operationId, AiOperationError.EMPTY_RESULT)
                        _uiState.update {
                            it.copy(
                                isAiWorking = false,
                                analyzingFileMessageIds = it.analyzingFileMessageIds - messageId,
                                aiFileAnalysisMode = null,
                                aiFileAnalysisName = null,
                                groupEncryptionWarning = text(R.string.chat_ai_file_failed)
                            )
                        }
                        return@fold
                    }
                    val resultText = response.text.trim().take(8_000)
                    val analysisKey = if (mode == AiFileAnalysisMode.QUESTION) {
                        "question:" + (question?.trim()?.take(120) ?: "default")
                    } else {
                        mode.wireValue
                    }
                    val current = _uiState.value.messages.firstOrNull { it.id == messageId } ?: message
                    val currentMeta = current.parsedMeta()
                    val updatedMeta = currentMeta.copy(
                        aiFileAnalyses = currentMeta.aiFileAnalyses + (analysisKey to resultText),
                        preferredFileAnalysisMode = mode.wireValue,
                        aiFileLastQuestion = if (mode == AiFileAnalysisMode.QUESTION) question?.trim()?.take(500) else currentMeta.aiFileLastQuestion
                    )
                    val updated = current.copy(
                        content = composeContentWithMeta(current.parsedContent(), updatedMeta),
                        meta = updatedMeta
                    )
                    if (!commitAiMessageResult(operationId, updated, request.userId, request.chatId)) return@fold
                    val displayFileResult = com.maodouchat.ai.AiPromptSafetyPolicy
                        .annotateIfPrivilegedHallucination(
                            resultText,
                            text(R.string.chat_ai_privilege_hallucination_disclaimer)
                        )
                    _uiState.update { state ->
                        state.copy(
                            isAiWorking = false,
                            analyzingFileMessageIds = state.analyzingFileMessageIds - messageId,
                            messages = state.messages.map { if (it.id == messageId) updated else it },
                            aiFileAnalysisResult = displayFileResult,
                            aiFileAnalysisMode = mode,
                            aiFileAnalysisName = response.fileName.take(120)
                        )
                    }
                },
                onFailure = { error ->
                    requireAiRequestCurrent(request)
                    failAiOperation(operationId, aiOperationErrorCode(error))
                    _uiState.update {
                        it.copy(
                            isAiWorking = false,
                            analyzingFileMessageIds = it.analyzingFileMessageIds - messageId,
                            aiFileAnalysisMode = null,
                            aiFileAnalysisName = null,
                            groupEncryptionWarning = error.message ?: text(R.string.chat_ai_file_failed)
                        )
                    }
                }
            )
        }
    }

    internal data class PreparedAiFile(val base64: String, val fileName: String, val mimeType: String)

    internal fun ChatDetailViewModel.resolveAiFileInput(message: Message, base64: String): PreparedAiFile? {
        val metadata = message.parsedMeta()
        val storedName = metadata.fileName?.trim()?.takeIf(String::isNotBlank)
        val cachedName = runCatching { Uri.parse(message.parsedContent()).lastPathSegment?.substringAfterLast('/') }.getOrNull()
        val candidateName = storedName ?: cachedName ?: "document"
        val extension = candidateName.substringAfterLast('.', "").lowercase()
        val canonicalMime = when (extension) {
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "md", "markdown" -> "text/markdown"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "xml" -> "application/xml"
            else -> null
        }
        if (canonicalMime != null) return PreparedAiFile(base64, candidateName.take(120), canonicalMime)

        val bytes = runCatching { android.util.Base64.decode(base64, android.util.Base64.NO_WRAP) }.getOrNull() ?: return null
        if (bytes.size >= 5 && bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))) {
            return PreparedAiFile(base64, "document.pdf", "application/pdf")
        }
        val textContent = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return null
        if (textContent.isBlank() || textContent.length > 120_000 || '\u0000' in textContent || '\uFFFD' in textContent) return null
        return PreparedAiFile(base64, "document.txt", "text/plain")
    }

    internal fun ChatDetailViewModel.translateTextMessage(
        messageId: String,
        targetLanguage: String,
        operationId: String? = null
    ) {
        // 密聊会话禁止 AI 翻译：解密明文不得送服务端 AI
        if (_uiState.value.isSecretChat == true) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_ai_blocked)) }
            return
        }
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_TRANSLATE)) {
            _uiState.update { it.copy(errorMessage = text(R.string.chat_ai_translate_disabled)) }
            return
        }

        if (_uiState.value.translatingMessageIds.contains(messageId)) {
            launchTrackedAiOperation(operationId) {
                failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
            }
            return
        }
        val message = _uiState.value.messages.firstOrNull { it.id == messageId && (it.type == MessageType.TEXT || it.type == MessageType.MARKDOWN) }
        if (message == null) {
            launchTrackedAiOperation(operationId) {
                failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
            }
            return
        }
        val sourceText = message.parsedContent().trim()
        if (sourceText.isBlank()) {
            launchTrackedAiOperation(operationId) {
                failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
            }
            return
        }
        val request = captureAiRequestSnapshot()
        if (request == null) {
            launchTrackedAiOperation(operationId) { failAiOperation(operationId, AiOperationError.CONTEXT_MISSING) }
            return
        }
        launchTrackedAiOperation(operationId) {
            requireAiRequestCurrent(request)
            _uiState.update {
                it.copy(
                    translatingMessageIds = it.translatingMessageIds + messageId,
                    groupEncryptionWarning = null
                )
            }
            requireAiRequestCurrent(request)
            val liveToken = tokenManager.getToken().orEmpty()
            ApiService.translateMessage(liveToken, sourceText.take(4_000), targetLanguage, request.chatId).fold(
                onSuccess = { response ->
                    requireAiRequestCurrent(request)
                    val translated = response.text.trim().take(4_000)
                    if (translated.isBlank()) {
                        failAiOperation(operationId, AiOperationError.EMPTY_RESULT)
                        _uiState.update {
                            it.copy(
                                translatingMessageIds = it.translatingMessageIds - messageId,
                                groupEncryptionWarning = text(R.string.chat_translation_empty)
                            )
                        }
                        return@fold
                    }
                    val current = _uiState.value.messages.firstOrNull { it.id == messageId } ?: message
                    val currentMeta = current.parsedMeta()
                    val updatedMeta = currentMeta.copy(
                        translations = currentMeta.translations + (response.targetLanguage to translated),
                        preferredTranslationLanguage = response.targetLanguage
                    )
                    val updated = current.copy(
                        content = composeContentWithMeta(current.parsedContent(), updatedMeta),
                        meta = updatedMeta
                    )
                    if (!commitAiMessageResult(operationId, updated, request.userId, request.chatId)) return@fold
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map { if (it.id == messageId) updated else it },
                            translatingMessageIds = state.translatingMessageIds - messageId,
                            groupEncryptionWarning = text(R.string.chat_translated)
                        )
                    }
                },
                onFailure = { error ->
                    requireAiRequestCurrent(request)
                    failAiOperation(operationId, aiOperationErrorCode(error))
                    _uiState.update {
                        it.copy(
                            translatingMessageIds = it.translatingMessageIds - messageId,
                            groupEncryptionWarning = error.message ?: text(R.string.chat_translation_failed)
                        )
                    }
                }
            )
        }
    }

    internal fun ChatDetailViewModel.maybeGenerateUnreadSummary(messages: List<Message>) {
        val state = _uiState.value
        val exactWindow = unreadSummaryWindow
        val unreadCount = exactWindow?.totalCount ?: state.chat?.unreadCount ?: 0
        if (unreadCount <= 0 || messages.isEmpty() || token.isBlank()) return
        if (!aiSettingsLoaded || !state.aiEnabled || !com.maodouchat.ai.AiPrivacyPreferences.consentAccepted(app)) return

        val exactMessageIds = exactWindow?.messageIds?.toHashSet()
        val candidates = messages
            .filter { message ->
                message.senderId != currentUserId &&
                    (message.type == MessageType.TEXT || message.type == MessageType.MARKDOWN) &&
                    !message.parsedMeta().aiAssisted &&
                    message.parsedContent().isNotBlank() &&
                    (exactMessageIds == null || message.id in exactMessageIds)
            }
            .sortedBy { it.timestamp }
            .takeLast(
                if (exactMessageIds == null) unreadCount.coerceIn(1, 24)
                else MAX_AI_SUMMARY_MESSAGES
            )

        if (candidates.isEmpty()) return
        val first = candidates.first()
        val last = candidates.last()
        val request = captureAiRequestSnapshot() ?: return
        val cacheKey = "${request.chatId}:${first.id}:${last.id}:${candidates.size}"
        // Only skip after a terminal success/definitive failure for this window.
        // Network blips must not permanently suppress auto unread summaries.
        if (unreadSummaryAttemptedForKey == cacheKey) return
        if (unreadSummaryInFlightKey == cacheKey || _uiState.value.isUnreadSummaryLoading) return

        val contextMessages = buildAiSummaryContextMessages(candidates)
        if (contextMessages.isEmpty()) return

        unreadSummaryInFlightKey = cacheKey
        val job = viewModelScope.launch {
            requireAiRequestCurrent(request)
            _uiState.update { it.copy(isUnreadSummaryLoading = true) }
            try {
                val cached = withContext(Dispatchers.IO) { aiSummaryRepo.getSummary(cacheKey) }
                requireAiRequestCurrent(request)
                if (cached != null) {
                    unreadSummaryAttemptedForKey = cacheKey
                    val displaySummary = AiPromptSafetyPolicy.annotateIfPrivilegedHallucination(
                        cached.summary,
                        text(R.string.chat_ai_privilege_hallucination_disclaimer)
                    )
                    _uiState.update {
                        it.copy(
                            unreadAiSummary = displaySummary,
                            unreadAiSummaryCount = cached.messageCount,
                            isUnreadSummaryLoading = false
                        )
                    }
                    viewModelScope.launch(Dispatchers.IO) {
                        aiSummarySyncRepo.push(
                            tokenManager.getToken().orEmpty(),
                            request.userId,
                            cached,
                            AiSummaryScope.UNREAD.name,
                            liveToken = tokenManager::getToken,
                            liveUserId = tokenManager::getUserId
                        )
                    }
                    return@launch
                }

                requireAiRequestCurrent(request)
                val liveToken = tokenManager.getToken().orEmpty()
                ApiService.summarizeChat(liveToken, contextMessages, style = "brief", chatId = request.chatId).fold(
                    onSuccess = { response ->
                        requireAiRequestCurrent(request)
                        unreadSummaryAttemptedForKey = cacheKey
                        val summary = response.summary.trim().take(3_000)
                        if (summary.isBlank()) {
                            _uiState.update { it.copy(isUnreadSummaryLoading = false) }
                            return@fold
                        }
                        val saved = withContext(Dispatchers.IO) {
                            aiSummaryRepo.saveSummary(
                                cacheKey = cacheKey,
                                chatId = request.chatId,
                                startMessageId = first.id,
                                endMessageId = last.id,
                                messageCount = candidates.size,
                                summary = summary
                            )
                        }
                        requireAiRequestCurrent(request)
                        val displaySummary = AiPromptSafetyPolicy.annotateIfPrivilegedHallucination(
                            summary,
                            text(R.string.chat_ai_privilege_hallucination_disclaimer)
                        )
                        _uiState.update {
                            it.copy(
                                unreadAiSummary = displaySummary,
                                unreadAiSummaryCount = candidates.size,
                                isUnreadSummaryLoading = false
                            )
                        }
                        viewModelScope.launch(Dispatchers.IO) {
                            aiSummarySyncRepo.push(
                                tokenManager.getToken().orEmpty(),
                                request.userId,
                                saved,
                                AiSummaryScope.UNREAD.name,
                                liveToken = tokenManager::getToken,
                                liveUserId = tokenManager::getUserId
                            )
                        }
                    },
                    onFailure = { error ->
                        requireAiRequestCurrent(request)
                        // Retryable transport errors leave the key unmarked so a later open can retry.
                        if (!isAmbiguousTransportFailure(error)) {
                            unreadSummaryAttemptedForKey = cacheKey
                        }
                        _uiState.update { it.copy(isUnreadSummaryLoading = false) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isAiRequestCurrent(request)) {
                    _uiState.update { it.copy(isUnreadSummaryLoading = false) }
                }
                throw error
            } catch (_: Throwable) {
                if (isAiRequestCurrent(request)) {
                    _uiState.update { it.copy(isUnreadSummaryLoading = false) }
                }
            } finally {
                if (unreadSummaryInFlightKey == cacheKey) {
                    unreadSummaryInFlightKey = null
                }
            }
        }
        unreadSummaryJob = job
        job.invokeOnCompletion {
            if (unreadSummaryJob === job) unreadSummaryJob = null
        }
    }

    internal fun ChatDetailViewModel.buildAiContextMessages(limit: Int): List<AiContextMessage> {
        return buildPlainAiContextMessages(
            messages = _uiState.value.messages,
            senders = aiContextSenders(),
            limit = limit
        )
    }

    internal fun ChatDetailViewModel.buildAiContextMessages(messages: List<Message>): List<AiContextMessage> {
        return buildPlainAiContextMessages(
            messages = messages,
            senders = aiContextSenders(),
            limit = messages.size.coerceAtLeast(1)
        )
    }

    internal fun ChatDetailViewModel.aiContextSenders(): AiContextSenders {
        val state = _uiState.value
        val fallback = if (state.chatIsGroup) {
            text(R.string.chat_other_person)
        } else {
            state.contact.displayName.ifBlank { text(R.string.chat_other_person) }
        }
        return AiContextSenders(
            currentUserId = currentUserId,
            currentUserLabel = text(R.string.chat_sender_me),
            fallbackLabel = fallback,
            namesByUserId = state.chat?.participants.orEmpty().associate { it.id to it.displayName }
        )
    }
