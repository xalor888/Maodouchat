package com.maodouchat.server.plugins

import io.ktor.http.Parameters

// getInviteLink 的 chatId：必填非空，缺省或全空白即无效（路由仍按原样报 chatId required）。
internal fun parseBotGetInviteLinkChatId(params: Parameters): String? =
    params["chatId"]?.takeIf { it.isNotBlank() }
