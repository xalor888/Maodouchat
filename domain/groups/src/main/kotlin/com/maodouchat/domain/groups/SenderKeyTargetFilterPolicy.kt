package com.maodouchat.domain.groups

import com.maodouchat.core.model.SenderKeyDistributionTargetDto

// 发送者密钥分发目标按状态筛选；自 GroupSenderKeyStatusSection 收敛。
object SenderKeyTargetFilterPolicy {

    fun filter(
        targets: List<SenderKeyDistributionTargetDto>,
        filter: SenderKeyTargetFilter
    ): List<SenderKeyDistributionTargetDto> =
        when (filter) {
            SenderKeyTargetFilter.ALL -> targets
            SenderKeyTargetFilter.FAILED ->
                targets.filter { it.status.equals("FAILED", ignoreCase = true) }
            SenderKeyTargetFilter.PENDING ->
                targets.filter { it.status.equals("PENDING", ignoreCase = true) }
            SenderKeyTargetFilter.SENT ->
                targets.filter { it.status.equals("SENT", ignoreCase = true) }
        }
}
