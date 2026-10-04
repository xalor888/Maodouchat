package com.maodouchat.server.plugins

import io.ktor.http.Parameters

// 原始取值：缺省回 null（ai-usage 的 feature 过滤，下游按 null/字符串区分处理）。
internal fun parseRawOrNull(params: Parameters, name: String): String? =
    params[name]

// 依次取首个存在的参数名（ai-usage 的用户过滤：userId 优先，缺省回退 q）。
internal fun parseFirstPresent(params: Parameters, vararg names: String): String? =
    names.firstNotNullOfOrNull { params[it] }

// 默认开启的开关（admin /chats 的 groupOnly）：只有字面量 "false" 关闭，其余（含缺省）全开。
internal fun parseDefaultTrueFlag(params: Parameters, name: String): Boolean =
    params[name] != "false"

// 非空判定（admin /posts 的 status）：缺省/全空白回 null，不 trim，原样透出。
internal fun parseNonBlankOrNull(params: Parameters, name: String): String? =
    params[name]?.takeIf { it.isNotBlank() }
