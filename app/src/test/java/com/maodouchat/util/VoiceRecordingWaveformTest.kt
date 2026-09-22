package com.maodouchat.util

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G212b：语音波形环形缓冲 + 录制阈值判定。
 *
 * 类的 KDoc 自己写着「纯逻辑，无 Android 依赖，**便于 JVM 单测**」——
 * 却零测试。环形缓冲是 off-by-one 的经典产地，而它坏的方式很隐蔽：
 * 波形会「错位/抖动」但完全不崩，肉眼在 UI 上还可能看不出规律。
 *
 * 这里钉三件事：
 * 1. **`snapshot()` 永远是最旧→最新**（不管写指针绕到哪）；
 * 2. **未填满时前面补 0**（长度恒定 = capacity，否则 Compose 侧会越界或画出半截）；
 * 3. **`push` 把振幅夹到 [0,1]**（上游给的 dB 值可能越界）。
 */
class VoiceRecordingWaveformTest {

    @Test
    fun emptySnapshotIsAllZerosOfFullLength() {
        val w = VoiceRecordingWaveform(capacity = 4)
        assertContentEquals(floatArrayOf(0f, 0f, 0f, 0f), w.snapshot(),
            "空缓冲的快照应是全 0 且长度等于 capacity")
    }

    @Test
    fun partialFillKeepsOrderAndPadsWithLeadingZeros() {
        val w = VoiceRecordingWaveform(capacity = 4)
        w.push(0.1f); w.push(0.2f); w.push(0.3f)
        // 最旧在前，未填满部分补 0
        assertContentEquals(floatArrayOf(0f, 0.1f, 0.2f, 0.3f), w.snapshot(),
            "部分填充应右边对齐、左边补 0")
    }

    @Test
    fun exactlyFullGivesNoPadding() {
        val w = VoiceRecordingWaveform(capacity = 3)
        w.push(0.1f); w.push(0.2f); w.push(0.3f)
        assertContentEquals(floatArrayOf(0.1f, 0.2f, 0.3f), w.snapshot())
    }

    @Test
    fun afterWraparoundSnapshotIsStillOldestFirst() {
        // 这是环形缓冲的核心性质：写指针绕回去之后，快照顺序不能乱
        val w = VoiceRecordingWaveform(capacity = 3)
        w.push(0.1f); w.push(0.2f); w.push(0.3f)   // [0.1, 0.2, 0.3]
        w.push(0.4f)                                // 0.1 被挤掉 → 最旧是 0.2
        assertContentEquals(floatArrayOf(0.2f, 0.3f, 0.4f), w.snapshot(),
            "绕回后应仍按最旧→最新输出")

        w.push(0.5f)
        assertContentEquals(floatArrayOf(0.3f, 0.4f, 0.5f), w.snapshot())
        w.push(0.6f)
        assertContentEquals(floatArrayOf(0.4f, 0.5f, 0.6f), w.snapshot(),
            "连续绕回多轮顺序仍须稳定")
    }

    @Test
    fun snapshotLengthIsAlwaysCapacity() {
        val w = VoiceRecordingWaveform(capacity = 5)
        repeat(12) { w.push(0.5f) }
        assertEquals(5, w.snapshot().size, "快照长度必须恒等于 capacity（超量推入也不变）")
    }

    @Test
    fun pushClampsAmplitudeToUnitRange() {
        val w = VoiceRecordingWaveform(capacity = 3)
        w.push(-0.5f); w.push(0.4f); w.push(7f)
        assertContentEquals(floatArrayOf(0f, 0.4f, 1f), w.snapshot(),
            "振幅必须被夹到 [0,1]——上游 dB 值可能越界")
    }

    @Test
    fun clearResetsToEmpty() {
        val w = VoiceRecordingWaveform(capacity = 3)
        w.push(0.5f); w.push(0.6f)
        w.clear()
        assertContentEquals(floatArrayOf(0f, 0f, 0f), w.snapshot(), "clear 后应回到全 0")
        // 且后续推入从最旧位开始（不是接着清空前的位置）
        w.push(0.7f)
        assertContentEquals(floatArrayOf(0f, 0f, 0.7f), w.snapshot(), "clear 后应从空态重新开始")
    }

    @Test
    fun capacityIsCoercedToAtLeastOne() {
        // capacity 传 0/负数不应让构造或 push 崩
        val w = VoiceRecordingWaveform(capacity = 0)
        w.push(0.5f)
        assertEquals(1, w.snapshot().size, "capacity 应被夹到至少 1")
    }

    @Test
    fun defaultCapacityIsTheDocumented56() {
        assertEquals(56, VoiceRecordingWaveform.DEFAULT_CAPACITY)
        assertEquals(56, VoiceRecordingWaveform().snapshot().size, "默认容量应生效")
    }

    // ---- 录制阈值 ----

    @Test
    fun minSendThresholdIsExclusiveAtTheBoundary() {
        assertFalse(VoiceCapturePolicy.canEnterPreview(499L), "差 1ms 不能进试听")
        assertTrue(VoiceCapturePolicy.canEnterPreview(500L), "正好 500ms 可以")
        assertTrue(VoiceCapturePolicy.canEnterPreview(5_000L))
        // 两个判定用同一个阈值（KDoc 说「与 ViewModel 校验一致」）
        listOf(0L, 499L, 500L, 1_000L).forEach { ms ->
            assertEquals(
                VoiceCapturePolicy.canEnterPreview(ms),
                VoiceCapturePolicy.canSendPreview(ms),
                "canEnterPreview 与 canSendPreview 的阈值必须一致，$ms 处不一致",
            )
        }
    }

    @Test
    fun holdHintFollowsCancelArmed() {
        assertEquals(
            VoiceCapturePolicy.HoldHint.RELEASE_TO_PREVIEW,
            VoiceCapturePolicy.holdHint(cancelArmed = false),
        )
        assertEquals(
            VoiceCapturePolicy.HoldHint.SLIDE_UP_CANCEL,
            VoiceCapturePolicy.holdHint(cancelArmed = true),
        )
    }
}
