package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallType
import com.maodouchat.core.model.Flag
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CallTypeFlagPolicyFuzzTest {

    private val random = Random(2026_11_22L)

    private val allTypes = listOf(CallType.VIDEO, CallType.AUDIO, CallType.GROUP)

    // 映射表的逐字复述：VIDEO 走视频开关，AUDIO/GROUP 共用语音开关。
    private fun oracleFlagFor(type: CallType): Flag =
        when (type) {
            CallType.VIDEO -> Flag("video_call_enabled", true)
            CallType.AUDIO, CallType.GROUP -> Flag("voice_call_enabled", true)
        }

    @Test
    fun `flagFor matches oracle over random call types`() {
        repeat(200) {
            val type = allTypes[random.nextInt(allTypes.size)]
            assertEquals(oracleFlagFor(type), CallTypeFlagPolicy.flagFor(type), "type=$type")
        }
    }

    @Test
    fun `flag table is pinned for every call type`() {
        assertEquals(Flag("video_call_enabled", true), CallTypeFlagPolicy.flagFor(CallType.VIDEO))
        assertEquals(Flag("voice_call_enabled", true), CallTypeFlagPolicy.flagFor(CallType.AUDIO))
        assertEquals(Flag("voice_call_enabled", true), CallTypeFlagPolicy.flagFor(CallType.GROUP))
    }

    @Test
    fun `video flag key differs from the shared voice flag key`() {
        val video = CallTypeFlagPolicy.flagFor(CallType.VIDEO)
        val audio = CallTypeFlagPolicy.flagFor(CallType.AUDIO)
        val group = CallTypeFlagPolicy.flagFor(CallType.GROUP)
        assertNotEquals(video.key, audio.key)
        assertEquals(audio, group)
        assertEquals(video, oracleFlagFor(CallType.VIDEO))
    }

    @Test
    fun `every flag is enabled by default over random types`() {
        repeat(100) {
            val type = allTypes[random.nextInt(allTypes.size)]
            val flag = CallTypeFlagPolicy.flagFor(type)
            assertEquals(true, flag.default, "type=$type")
        }
    }
}
