package com.maodouchat.ui.theme

/**
 * 主题风格家族。MAODOU（白底液态玻璃）为品牌默认；TG_* 为 Telegram/Nekogram 扁平主题，
 * 色板见下方 Tg*Scheme/Palette（已按 WCAG 4.5:1 校准）。
 */
enum class ThemeFamily(val id: String) {
    MAODOU("maodou"),
    TG_CLASSIC("tg_classic"),
    TG_MIDNIGHT("tg_midnight"),
    TG_GRAPHITE("tg_graphite");

    val isLiquidGlass: Boolean get() = this == MAODOU
    val isTelegram: Boolean get() = this == TG_CLASSIC || this == TG_MIDNIGHT || this == TG_GRAPHITE

    companion object {
        fun normalize(raw: String?): ThemeFamily = when (raw?.trim()?.lowercase()) {
            "tg_classic", "tg", "telegram", "telegram_classic" -> TG_CLASSIC
            "tg_midnight", "midnight" -> TG_MIDNIGHT
            "tg_graphite", "graphite" -> TG_GRAPHITE
            else -> MAODOU
        }

        val ALL: List<ThemeFamily> = listOf(MAODOU, TG_CLASSIC, TG_MIDNIGHT, TG_GRAPHITE)
        val PICKABLE: List<ThemeFamily> = listOf(MAODOU)
    }
}
