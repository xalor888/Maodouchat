package com.maodouchat.ai.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 本地语义排序簇：不走模型，按分词命中计分。函数体从 LocalAiGateway 逐字搬出。
internal object LocalAiLocalRanking {
    // 查询分词正则放对象级复用，避免每次调用重复编译。
    private val queryTokenSplitRegex = Regex("\\s+")

    suspend fun rankSemantic(
        query: String,
        candidates: List<Pair<String, String>>
    ): List<String> = withContext(Dispatchers.Default) {
        rankSemanticScored(query, candidates).map { it.first }
    }

    suspend fun rankSemanticScored(
        query: String,
        candidates: List<Pair<String, String>>
    ): List<Pair<String, Double>> = withContext(Dispatchers.Default) {
        val q = query.trim().lowercase()
        if (q.isBlank()) return@withContext emptyList()
        val tokens = q.split(queryTokenSplitRegex).filter { it.isNotBlank() }.take(12)
        candidates
            .map { (id, text) ->
                val hay = text.lowercase()
                val score = tokens.count { token -> hay.contains(token) }.toDouble()
                id to score
            }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
    }
}
