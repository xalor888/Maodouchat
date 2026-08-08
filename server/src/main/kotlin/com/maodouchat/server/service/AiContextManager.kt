package com.maodouchat.server.service

import com.maodouchat.server.model.AiContextMessage
import com.maodouchat.server.model.AiSemanticSearchCandidate

/**
 * AI 上下文管理 —— 面向网关热路径的 token 预算上下文/候选选择。
 *
 * 注意：此处刻意**不做**上下文缓存。缓存会(a)跨请求复用旧上下文造成结果陈旧，
 * (b)在服务端内存留存用户明文（与服务端不接触 E2EE 明文、最小化留存的原则冲突）。
 * 每次请求由调用方现取现选，成本由 token 预算控制。
 */
class AiContextManager(
    private val maxContextTokens: Int = 120_000,
    private val reservedOutputTokens: Int = 4096
) {
    /**
     * 估算字符串的 token 数。
     * 统一委托给 [AiStreamingService.estimateTokens]（CJK 感知版本），避免多份副本公式漂移。
     */
    fun estimateTokens(text: String): Int = AiStreamingService.estimateTokens(text)

    /**
     * 面向网关热路径的 token 预算上下文选择。
     *
     * 输入是 [AiContextMessage]（仅有 sender/text，无时间戳），按"最近优先"贪心选取，
     * 直到累计 token 达到 [budgetTokens]；至少保留最后一条消息，避免返回空。
     * 返回值保持原始时间顺序。调用方在结果为空时应回退到 takeLast。
     */
    fun selectContext(messages: List<AiContextMessage>, budgetTokens: Int): List<AiContextMessage> {
        if (messages.isEmpty()) return emptyList()
        val budget = budgetTokens.coerceAtLeast(0)
        val selected = ArrayDeque<AiContextMessage>()
        var used = 0
        // 从尾部（最新）向前贪心选取
        for (msg in messages.asReversed()) {
            val cost = estimateTokens("${msg.sender}: ${msg.text}")
            if (selected.isNotEmpty() && used + cost > budget) break
            selected.addFirst(msg)
            used += cost
            if (used >= budget) break
        }
        return selected.toList()
    }

    /**
     * 面向语义搜索的 token 预算候选裁剪。按原始顺序从前到后贪心选取，直到累计 token 达到 [budgetTokens]；
     * 至少保留第一条候选，避免返回空。调用方在结果为空时应回退到 take。
     */
    fun selectCandidates(
        candidates: List<AiSemanticSearchCandidate>,
        budgetTokens: Int
    ): List<AiSemanticSearchCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val budget = budgetTokens.coerceAtLeast(0)
        val selected = mutableListOf<AiSemanticSearchCandidate>()
        var used = 0
        for (candidate in candidates) {
            val cost = estimateTokens("${candidate.sender}: ${candidate.text}")
            if (selected.isNotEmpty() && used + cost > budget) break
            selected.add(candidate)
            used += cost
            if (used >= budget) break
        }
        return selected
    }
}
