package com.maodouchat.group.play

import org.json.JSONArray
import org.json.JSONObject

/**
 * 群投票玩法策略（U06 解耦）。
 * 封装投票 Payload 构建、解析及选项校验。
 */
object GroupPollPolicy {
    const val POLL_PREFIX = "POLL:"

    fun buildPollPayload(
        pollId: String,
        question: String,
        options: List<String>,
        multi: Boolean,
        anonymous: Boolean
    ): String {
        val o = JSONObject()
        o.put("id", pollId)
        o.put("q", question.take(200))
        o.put("options", JSONArray(options.map { it.take(80) }))
        o.put("multi", multi)
        o.put("anonymous", anonymous)
        return POLL_PREFIX + o.toString()
    }

    fun parsePoll(content: String): JSONObject? {
        if (!content.startsWith(POLL_PREFIX)) return null
        return runCatching { JSONObject(content.removePrefix(POLL_PREFIX)) }.getOrNull()
    }
}
