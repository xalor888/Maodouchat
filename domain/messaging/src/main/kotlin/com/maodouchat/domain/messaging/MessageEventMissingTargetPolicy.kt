package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageMutationKind
import com.maodouchat.core.model.MessagingV2EventAction

/**
 * 事件投影里目标消息缺失时的处置判定。纯逻辑：调用方按返回结果落墓碑、
 * 直接跳过或报错，这里不碰 DAO、不碰时钟。
 */
object MessageEventMissingTargetPolicy {

    /** 目标缺失时事件的去向。 */
    sealed interface MissingTargetDecision {
        /** 落一条终态墓碑，等目标迟到时也不再投影。 */
        data class Tombstone(val kind: MessageMutationKind) : MissingTargetDecision

        /** 该事件本就不需要目标，直接跳过。 */
        data object NoOp : MissingTargetDecision

        /** 目标缺失是不应发生的，调用方报错。 */
        data object Error : MissingTargetDecision
    }

    /**
     * 目标消息不存在时事件的去向。终态动作（撤回/删除）优先落墓碑——
     * 删除同时在无操作集里，但墓碑分支先判，行为与原 if/else 链一致。
     */
    fun decide(action: String): MissingTargetDecision = when {
        action in TERMINAL_ACTIONS -> MissingTargetDecision.Tombstone(
            kind = if (action == MessagingV2EventAction.REVOKE) {
                MessageMutationKind.REVOKE
            } else {
                MessageMutationKind.DELETE
            },
        )
        action in MISSING_TARGET_NO_OP_ACTIONS -> MissingTargetDecision.NoOp
        else -> MissingTargetDecision.Error
    }

    private val TERMINAL_ACTIONS = setOf(
        MessagingV2EventAction.DELETE,
        MessagingV2EventAction.REVOKE,
    )
    private val MISSING_TARGET_NO_OP_ACTIONS = setOf(
        MessagingV2EventAction.DELETE,
        MessagingV2EventAction.DELIVERY_RECEIPT,
        MessagingV2EventAction.READ_RECEIPT,
        MessagingV2EventAction.PLAY_RECEIPT,
    )
}
