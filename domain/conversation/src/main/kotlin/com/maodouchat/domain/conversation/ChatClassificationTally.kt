package com.maodouchat.domain.conversation

import com.maodouchat.core.model.AiMessageClassifierCategory
import com.maodouchat.core.model.CategoryCount
import com.maodouchat.core.model.Classification

/**
 * 会话消息分类统计：按类别计数、置信度取类别内均值，按计数降序。
 * 调用方负责取数（Room 查询仍在 app 侧）与落库。
 */
object ChatClassificationTally {

    /** 按类别汇总单条分类结果；置信度是该类别的均值，不是全会话均值。 */
    fun tally(results: List<Classification>): List<CategoryCount> {
        val counts = HashMap<AiMessageClassifierCategory, Int>()
        val confSums = HashMap<AiMessageClassifierCategory, Double>()
        for (result in results) {
            counts[result.category] = (counts[result.category] ?: 0) + 1
            confSums[result.category] = (confSums[result.category] ?: 0.0) + result.confidence
        }
        return counts.map { (category, count) ->
            CategoryCount(
                category = category.wire,
                count = count,
                confidence = (confSums[category] ?: 0.0) / count
            )
        }.sortedByDescending { it.count }
    }
}
