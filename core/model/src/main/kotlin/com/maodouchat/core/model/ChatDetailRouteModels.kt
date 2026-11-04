package com.maodouchat.core.model

/** 8.43：图片发送前预览的待确认项（URI + 单次查看/剧透标记，选取时刻捕获）。 */
enum class ParticleAction { DELETE, REVOKE }

data class TranslationLanguageOption(
    val wireValue: String,
    val labelResource: Int
)
