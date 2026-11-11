package com.maodouchat.domain.messaging

/**
 * 消息变更控制器向 UI 写入的文案种类。domain 侧只定种类，真正的本地化字符串由 app 侧
 * chatMessageMutationTextResId 映射提供——domain 模块不引用 app 的 R。
 */
enum class ChatMessageMutationText {
    FEATURE_DISABLED,
    SESSION_EXPIRED,
    DELETE_SERVER_FAILED,
    MESSAGE_REVOKED_PLACEHOLDER,
    REVOKE_FAILED,
    EDIT_EMPTY,
    EDIT_NOT_ALLOWED,
    EDIT_FAILED,
    MUTATION_COMMITTED_LOCAL_SYNC_PENDING,
    REACTION_FAILED,
}
