package com.maodouchat.core.model

import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json

/** AI 记账状态：流式任务 Job、重试队列/时间表、请求门与最近一次请求参数。从 ChatDetailDeps 抽出，纯搬移。 */
class ChatDetailAiBookkeeping {
    var pendingAiAction: PendingAiAction? = null
    var pendingAiOperationRetryId: String? = null
    var aiSettingsLoaded = false
    var unreadSummaryAttemptedForKey: String? = null
    /** In-flight auto unread summary key; prevents concurrent duplicate summarizeChat calls. */
    var unreadSummaryInFlightKey: String? = null
    val semanticSearchGate = AiRequestGenerationGate()
    val aiOperationJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    val aiAutoRetryJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    val aiAutoRetryAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    val aiOperationQueueMutex = Mutex()
    val aiOperationJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    var aiRewriteStreamJob: kotlinx.coroutines.Job? = null
    var aiReplyStreamJob: kotlinx.coroutines.Job? = null
    var semanticSearchJob: kotlinx.coroutines.Job? = null
    var groupAiJob: kotlinx.coroutines.Job? = null
    var manualSummaryJob: kotlinx.coroutines.Job? = null
    var unreadSummaryJob: kotlinx.coroutines.Job? = null
    val aiRewriteGate = AiRequestGenerationGate()
    val aiReplyGate = AiRequestGenerationGate()
    val groupAiGate = AiRequestGenerationGate()
    val manualSummaryGate = AiRequestGenerationGate()
    var lastAiRewriteMode = "polish"
    var lastAiRewriteTargetLanguage: String? = null
    var lastAiReplyTone: String = "friendly"
}
