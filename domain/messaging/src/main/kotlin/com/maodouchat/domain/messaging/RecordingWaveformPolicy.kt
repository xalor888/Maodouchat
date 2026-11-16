package com.maodouchat.domain.messaging

// 录音波形指示条重采样与高度映射：空波形全 0.08、按比例重采样到 32 条、末 3 条叠加瞬时振幅。
object RecordingWaveformPolicy {

    const val BAR_COUNT: Int = 32
    const val EMPTY_BAR_HEIGHT: Float = 0.08f
    const val LIVE_BOOST_BARS: Int = 3
    const val MIN_HEIGHT_FRACTION: Float = 0.12f

    fun resample(
        waveform: List<Float>,
        liveAmplitude: Float,
        barCount: Int = BAR_COUNT,
    ): List<Float> {
        val base = if (waveform.isEmpty()) {
            List(barCount) { EMPTY_BAR_HEIGHT }
        } else {
            List(barCount) { i ->
                val idx = ((i.toFloat() / (barCount - 1).coerceAtLeast(1)) *
                    (waveform.size - 1).coerceAtLeast(0)).toInt()
                    .coerceIn(0, waveform.lastIndex.coerceAtLeast(0))
                waveform.getOrElse(idx) { 0f }.coerceIn(0f, 1f)
            }
        }
        // 末尾条跟瞬时振幅，增强“正在说话”感
        return base.mapIndexed { i, v ->
            if (i >= barCount - LIVE_BOOST_BARS) {
                maxOf(v, liveAmplitude * (0.7f + 0.1f * (i - (barCount - LIVE_BOOST_BARS))))
            } else {
                v
            }
        }
    }

    fun barHeightFraction(h: Float): Float =
        (MIN_HEIGHT_FRACTION + h * 0.88f).coerceIn(MIN_HEIGHT_FRACTION, 1f)
}
