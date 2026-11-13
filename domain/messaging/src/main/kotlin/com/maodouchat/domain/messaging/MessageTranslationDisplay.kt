package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageMeta

// 展示用译文：优先用户选的语言，回退中文，再回退最后一个非空译文。自 app 侧 ChatDetailLocalizedLabels.kt 逐字平移。
fun MessageMeta.displayedTranslation(): String? {
    val preferred = preferredTranslationLanguage?.let(translations::get)
    return preferred?.takeIf { it.isNotBlank() }
        ?: translations["中文"]?.takeIf { it.isNotBlank() }
        ?: translations.values.lastOrNull { it.isNotBlank() }
}
