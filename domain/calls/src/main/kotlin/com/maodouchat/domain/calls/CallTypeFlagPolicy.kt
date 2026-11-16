package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallType
import com.maodouchat.core.model.Flag

// 通话类型→功能开关：开关 key/default 与 app 侧 RuntimeFlags 同源，
// isEnabled 按 key 查 SharedPreferences，domain 侧构造等值 Flag 行为一致。
object CallTypeFlagPolicy {

    fun flagFor(type: CallType): Flag =
        when (type) {
            CallType.VIDEO -> Flag("video_call_enabled", true)
            CallType.AUDIO, CallType.GROUP -> Flag("voice_call_enabled", true)
        }
}
