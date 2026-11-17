package com.maodouchat.domain.messaging

// PDF 渲染页数与单页尺寸纯规则；渲染本身仍在 app 侧。
object LocalAiPdfPagePolicy {
    const val MAX_PDF_PAGES = 4
    const val MAX_PAGE_WIDTH = 1024
    const val MAX_RENDER_HEIGHT = 2_048
    const val MIN_PAGE_SIDE = 64
    const val MAX_SCALE = 2f
    const val MIN_SCALE = 0.25f

    fun cappedPageCount(pageCount: Int): Int = pageCount.coerceAtMost(MAX_PDF_PAGES)

    fun renderSize(pageWidth: Int, pageHeight: Int): Pair<Int, Int> {
        val scale = (MAX_PAGE_WIDTH.toFloat() / pageWidth.coerceAtLeast(1))
            .coerceAtMost(MAX_SCALE)
            .coerceAtLeast(MIN_SCALE)
        val width = (pageWidth * scale).toInt().coerceIn(MIN_PAGE_SIDE, MAX_PAGE_WIDTH)
        val height = (pageHeight * scale).toInt().coerceIn(MIN_PAGE_SIDE, MAX_RENDER_HEIGHT)
        return width to height
    }
}
