package com.maodouchat.ui.screen.chatdetail

import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json

/** AI 记账状态：流式任务 Job、重试队列/时间表、请求门与最近一次请求参数。从 ChatDetailDeps 抽出，纯搬移。 */
internal class ChatDetailAiBookkeeping {
    internal var pendingAiAction: PendingAiAction? = null
    internal var pendingAiOperationRetryId: String? = null
    internal var aiSettingsLoaded = false
    internal var unreadSummaryAttemptedForKey: String? = null
    /** In-flight auto unread summary key; prevents concurrent duplicate summarizeChat calls. */
    internal var unreadSummaryInFlightKey: String? = null
    internal val semanticSearchGate = AiRequestGenerationGate()
    internal val aiOperationJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    internal val aiAutoRetryJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    internal val aiAutoRetryAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    internal val aiOperationQueueMutex = Mutex()
    internal val aiOperationJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    internal var aiRewriteStreamJob: kotlinx.coroutines.Job? = null
    internal var aiReplyStreamJob: kotlinx.coroutines.Job? = null
    internal var semanticSearchJob: kotlinx.coroutines.Job? = null
    internal var groupAiJob: kotlinx.coroutines.Job? = null
    internal var manualSummaryJob: kotlinx.coroutines.Job? = null
    internal var unreadSummaryJob: kotlinx.coroutines.Job? = null
    internal val aiRewriteGate = AiRequestGenerationGate()
    internal val aiReplyGate = AiRequestGenerationGate()
    internal val groupAiGate = AiRequestGenerationGate()
    internal val manualSummaryGate = AiRequestGenerationGate()
    internal var lastAiRewriteMode = "polish"
    internal var lastAiRewriteTargetLanguage: String? = null
    internal var lastAiReplyTone: String = "friendly"
}
