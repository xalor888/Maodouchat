package com.maodouchat.server.plugins

import io.ktor.http.Parameters

// getFile 的 id：messageId 优先、空白则回退 fileId，全空白即无效（路由仍按原样报 messageId or fileId required）。
internal fun parseBotGetFileId(params: Parameters): String? =
    params["messageId"].orEmpty().ifBlank { params["fileId"].orEmpty() }.takeIf { it.isNotBlank() }
