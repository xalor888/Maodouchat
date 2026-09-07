package com.maodouchat.call

import com.maodouchat.webrtc.GroupCallPolicy

/**
 * P03：群通话能力边界。Mesh 上限与 [GroupCallPolicy.MAX_MESH_MEMBERS] 对齐；
 * SFU / 屏幕共享 / 录制明确未实现，禁止在 UI 或文档中伪装为已完成。
 */
object GroupCallCapabilities {
    const val MAX_MESH_MEMBERS: Int = GroupCallPolicy.MAX_MESH_MEMBERS

    const val SFU_SUPPORTED: Boolean = false
    const val SCREEN_SHARE_SUPPORTED: Boolean = false
    const val CALL_RECORDING_SUPPORTED: Boolean = false

    /** 含自己的成员总数是否落在可启动的 mesh 区间（至少 2 人、至多上限）。 */
    fun canStartMesh(memberCountIncludingSelf: Int): Boolean =
        memberCountIncludingSelf in 2..MAX_MESH_MEMBERS

    fun isUnimplemented(feature: Feature): Boolean = when (feature) {
        Feature.SFU -> !SFU_SUPPORTED
        Feature.SCREEN_SHARE -> !SCREEN_SHARE_SUPPORTED
        Feature.CALL_RECORDING -> !CALL_RECORDING_SUPPORTED
    }

    enum class Feature {
        SFU,
        SCREEN_SHARE,
        CALL_RECORDING,
    }
}
