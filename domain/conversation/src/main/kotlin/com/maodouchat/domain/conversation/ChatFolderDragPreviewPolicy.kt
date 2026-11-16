package com.maodouchat.domain.conversation

import kotlin.math.roundToInt

// 会话文件夹拖拽排序「预览落点」换算：拖动位移换算成目标下标；-1 表示未拖拽/无效。
object ChatFolderDragPreviewPolicy {

    fun dragPreviewTarget(dragIndex: Int, dragOffsetY: Float, rowPitchPx: Float, size: Int): Int {
        if (dragIndex < 0 || rowPitchPx <= 0f) return -1
        val shift = (dragOffsetY / rowPitchPx).roundToInt()
        return (dragIndex + shift).coerceIn(0, size - 1)
    }
}
