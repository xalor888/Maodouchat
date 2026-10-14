package com.maodouchat.ui.screen.chatdetail

/**
 * 气泡在窗口中的位置与尺寸（G85 从 `ChatDetailRoute.kt` 连同 item 渲染一起搬来）。
 *
 * 粒子删除动效需要知道「哪个气泡碎在哪」——`onBoundsMeasured` 回报后由 Route 存进 map，
 * `startParticleEffect` 再取出。原为 Route 文件的 private data class，
 * 随渲染一起搬到本文件并改 `internal`（internal Composable 不能暴露 private-in-file 类型）。
 */
internal data class BubbleBounds(
    val offset: androidx.compose.ui.unit.IntOffset,
    val size: androidx.compose.ui.unit.IntSize
)
