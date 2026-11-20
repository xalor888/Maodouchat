package com.maodouchat.domain.conversation

/**
 * 钉钉式密聊：独立 1:1 会话（chatType=SECRET），双方同步。
 * 群/频道不能开密聊；不得把现有普通会话改成密聊。
 */
object SecretChatPolicy {

    const val CHAT_TYPE = "SECRET"

    fun isSecretChatType(chatType: String?): Boolean =
        chatType?.trim()?.uppercase() == CHAT_TYPE

    /** 只有普通单聊可以「发起」一场新的密聊。 */
    fun canStartFromDirect(isGroup: Boolean, chatType: String?): Boolean {
        if (isGroup) return false
        if (isSecretChatType(chatType)) return false
        val type = chatType?.trim()?.uppercase().orEmpty()
        return type.isEmpty() || type == "DIRECT"
    }

    fun canCreateSecret(isGroup: Boolean, participantCount: Int): Boolean =
        !isGroup && participantCount == 2


    fun allowInCustomOrLockedFolder(isSecret: Boolean): Boolean = !isSecret
}
