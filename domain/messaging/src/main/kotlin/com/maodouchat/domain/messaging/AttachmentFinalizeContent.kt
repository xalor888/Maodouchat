package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta

/** 收尾消息正文拼装：正文 + `<meta>`{meta JSON}`</meta>`。与 MessageJsonPolicy.composeContentWithMeta 不同：默认 meta 仍保留 meta 段，且不剥离正文里的 `<meta>` 字面量。 */
fun composeFinalizeContent(text: String, meta: MessageMeta): String {
    val encoded = MessageJsonPolicy.encodeMessageMeta(meta)
    return "$text${Message.META_TAG_PREFIX}$encoded</meta>"
}
