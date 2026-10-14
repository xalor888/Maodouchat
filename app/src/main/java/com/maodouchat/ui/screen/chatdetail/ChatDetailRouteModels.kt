package com.maodouchat.ui.screen.chatdetail

/** 8.43：图片发送前预览的待确认项（URI + 单次查看/剧透标记，选取时刻捕获）。 */
internal enum class ParticleAction { DELETE, REVOKE }

internal data class TranslationLanguageOption(
    val wireValue: String,
    val labelResource: Int
)
