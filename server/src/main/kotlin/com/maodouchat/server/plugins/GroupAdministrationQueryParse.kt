package com.maodouchat.server.plugins

import io.ktor.http.Parameters

// 可选 Long 查询参数（sender-key 分发状态的 epoch）：缺省/非法回 null，仓库层按“未指定”处理。
internal fun parseOptionalLong(params: Parameters, name: String): Long? =
    params[name]?.toLongOrNull()
