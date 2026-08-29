package com.maodouchat.domain.messaging

/** 消息变更类型（M06）。 */
enum class MutationKind { EDIT, REVOKE, DELETE, REACT }

/** 消息变更命令（M06）：一次 mutation 一个命令，幂等。 */
data class MessageMutationCommand(
    val messageId: String,
    val kind: MutationKind,
    /** EDIT 时的新正文。 */
    val newText: String? = null,
    /** REACT 时的 emoji。 */
    val emoji: String? = null,
)

sealed interface MessageMutationResult {
    data class Success(val terminal: Boolean) : MessageMutationResult
    data class Failure(val reason: MutationFailureReason) : MessageMutationResult
}

enum class MutationFailureReason {
    NOT_FOUND,
    FORBIDDEN,
    CONFLICT,
    PERMANENT,
    CANCELLED,
}

/** 消息变更门面（M06）：编辑、撤回、删除、回应全部经此唯一入口。 */
interface MessagingV2MutationFacade {
    suspend fun mutate(command: MessageMutationCommand): MessageMutationResult
}
