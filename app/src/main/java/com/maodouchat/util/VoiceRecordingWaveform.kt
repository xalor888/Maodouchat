package com.maodouchat.util

/**
 * 录音波形环形缓冲：把瞬时振幅推入固定长度历史，供 Compose 绘制。
 * 纯逻辑，无 Android 依赖，便于 JVM 单测。
 */
class VoiceRecordingWaveform(
    val capacity: Int = DEFAULT_CAPACITY,
) {
    private val samples = FloatArray(capacity.coerceAtLeast(1))
    private var writeIndex: Int = 0
    private var filled: Int = 0

    fun push(amplitude: Float) {
        val v = amplitude.coerceIn(0f, 1f)
        samples[writeIndex] = v
        writeIndex = (writeIndex + 1) % samples.size
        if (filled < samples.size) filled++
    }

    /** 从最旧到最新的快照；未填满时前面为 0。 */
    fun snapshot(): FloatArray {
        val out = FloatArray(samples.size)
        if (filled == 0) return out
        val start = if (filled < samples.size) 0 else writeIndex
        for (i in 0 until filled) {
            out[samples.size - filled + i] = samples[(start + i) % samples.size]
        }
        return out
    }

    fun clear() {
        samples.fill(0f)
        writeIndex = 0
        filled = 0
    }

    companion object {
        const val DEFAULT_CAPACITY = 56
    }
}

/**
 * 语音录制/试听状态机决策（纯函数）。
 */
object VoiceCapturePolicy {
    /** 最短可发送时长（与 ViewModel 校验一致）。 */
    const val MIN_SEND_MS: Long = 500L

    data class PreviewSnapshot(
        val filePath: String,
        val durationMs: Long,
    )

    /**
     * 松手结束录制后：是否进入试听，还是直接丢弃/发送由 UI 决定。
     * duration 过短 → 不可试听也不可发送。
     */
    fun canEnterPreview(durationMs: Long): Boolean = durationMs >= MIN_SEND_MS

    fun canSendPreview(durationMs: Long): Boolean = durationMs >= MIN_SEND_MS

    /**
     * 上滑取消已由手势处理；此处描述录制 UI 提示文案 key 语义。
     */
    enum class HoldHint {
        RELEASE_TO_PREVIEW,
        SLIDE_UP_CANCEL,
    }

    fun holdHint(cancelArmed: Boolean): HoldHint =
        if (cancelArmed) HoldHint.SLIDE_UP_CANCEL else HoldHint.RELEASE_TO_PREVIEW
}
