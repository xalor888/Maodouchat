package com.maodouchat.server.service

import com.maodouchat.server.repository.ModerationRuleRepository
import org.slf4j.LoggerFactory

/**
 * 动态/评论：关键词规则先跑，再可选 AI 分类。
 * 上游失败 / 未配置 / 开关关 → 不拦（失败开放）。聊天密文不进这里。
 */
object ContentModerationService {
    private val log = LoggerFactory.getLogger("ContentModeration")

    suspend fun combine(
        userId: String,
        source: String,
        content: String,
        keyword: ModerationRuleRepository.Evaluation,
        gateway: AiGateway,
        rules: ModerationRuleRepository
    ): ModerationRuleRepository.Evaluation {
        if (keyword.blocked) return keyword
        val ai = classify(userId, source, content, gateway, rules) ?: return keyword
        return ModerationRuleRepository.Evaluation(
            blocked = ai.blocked,
            action = ai.action ?: keyword.action,
            message = ai.message ?: keyword.message,
            matches = keyword.matches + ai.matches
        )
    }

    private suspend fun classify(
        userId: String,
        source: String,
        content: String,
        gateway: AiGateway,
        rules: ModerationRuleRepository
    ): ModerationRuleRepository.Evaluation? {
        if (!RuntimeConfigService.isAiContentModerationEnabled()) return null
        val bounded = content.trim().take(AiContentModerationPolicy.MAX_INPUT_CHARS)
        if (bounded.isEmpty()) return null
        val raw = try {
            gateway.classifyContent(source, bounded)
        } catch (error: Exception) {
            log.warn("AI content classify threw source={} user={}", source, userId, error)
            return null
        }
        val text = when (raw) {
            is AiGatewayResult.Success -> raw.value
            AiGatewayResult.NotConfigured -> {
                log.info("AI content classify skipped: OpenAI not configured")
                return null
            }
            is AiGatewayResult.UpstreamError -> {
                log.warn("AI content classify upstream {} {}", raw.statusCode, raw.message)
                return null
            }
            is AiGatewayResult.InvalidResponse -> {
                log.warn("AI content classify invalid: {}", raw.message)
                return null
            }
        }
        val decision = AiContentModerationPolicy.parse(text)
        if (decision.verdict == AiContentModerationPolicy.Verdict.ALLOW) return null
        val eventId = rules.recordAiDecision(
            userId = userId,
            source = source,
            action = decision.action,
            matched = AiContentModerationPolicy.matchedPreview(decision),
            needsReview = decision.needsReview
        )
        return ModerationRuleRepository.Evaluation(
            blocked = decision.verdict == AiContentModerationPolicy.Verdict.BLOCK,
            action = decision.action,
            message = if (decision.verdict == AiContentModerationPolicy.Verdict.BLOCK) {
                "内容已进入安全审核，请稍后再试"
            } else {
                null
            },
            matches = listOf(
                ModerationRuleRepository.RuleMatch(
                    ruleId = AiContentModerationPolicy.RULE_ID,
                    action = decision.action,
                    matched = AiContentModerationPolicy.matchedPreview(decision),
                    needsReview = decision.needsReview,
                    eventId = eventId
                )
            )
        )
    }
}
