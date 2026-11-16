package com.maodouchat.domain.messaging

import org.json.JSONObject

// Agent 工具参数纯解析，自 app 侧 AgentToolHost 收敛；
// 执行分发与 TokenManager 读取仍留在 app 侧。
object AgentToolArgs {

    fun parseArgs(raw: String): Map<String, String> {
        val obj = runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrNull() ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        obj.keys().forEach { key ->
            val value = obj.opt(key) ?: return@forEach
            out[key] = value.toString()
        }
        return out
    }

    fun parseBool(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> null
    }

    fun fail(error: Throwable): String = "Error: ${error.message ?: error.javaClass.simpleName}"
}
