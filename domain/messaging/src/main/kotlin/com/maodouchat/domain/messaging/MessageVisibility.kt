package com.maodouchat.domain.messaging

/**
 * 消息查询/呈现的可见性作用域。
 */
enum class MessageVisibilityScope {
    /** 全局消息搜索（消息跨会话检索） */
    GLOBAL_SEARCH,

    /** 会话内搜索（特定会话内部消息过滤） */
    CONVERSATION_SEARCH,

    /** 星标消息列表（全局或汇总呈现） */
    STARRED_LIST,

    /** 媒体中心/画廊（本地媒体聚合） */
    MEDIA_GALLERY,
}

/**
 * 消息可见性判断所需的会话与消息状态上下文。
 */
data class MessageVisibilityContext(
    /** 是否属于密聊会话 */
    val isSecretChat: Boolean,
    /** 会话是否配置了 PIN 锁 */
    val isChatLocked: Boolean,
    /** 当前会话是否在本次运行时已完成 PIN 解锁 */
    val isChatUnlockedInSession: Boolean = false,
    /** 消息是否已被撤回 */
    val isRevoked: Boolean = false,
    /** 消息是否已被删除（或墓碑标记） */
    val isDeleted: Boolean = false,
)

/**
 * 统一 Message Visibility Policy。
 * 保证星标、全局搜索、会话搜索和媒体库遵守一致的密聊、PIN 锁和删除/撤回边界。
 */
object MessageVisibilityPolicy {

    /**
     * 判断一条消息在指定作用域 [scope] 下是否对用户可见或可检索。
     */
    fun isVisible(
        scope: MessageVisibilityScope,
        context: MessageVisibilityContext,
    ): Boolean {
        // 已删除或已撤回的消息在任何检索和汇总场景均不可见
        if (context.isDeleted || context.isRevoked) {
            return false
        }

        return when (scope) {
            MessageVisibilityScope.GLOBAL_SEARCH -> {
                // 全局搜索：绝不泄漏密聊内容，且排除锁定会话
                !context.isSecretChat && !context.isChatLocked
            }
            MessageVisibilityScope.STARRED_LIST -> {
                // 星标列表：密聊消息不可加星/在全局星标呈现，锁定会话也必须排除
                !context.isSecretChat && !context.isChatLocked
            }
            MessageVisibilityScope.CONVERSATION_SEARCH -> {
                // 会话内搜索：密聊会话在其内部允许搜索；若该会话有锁，则必须已完成运行时解锁
                !context.isChatLocked || context.isChatUnlockedInSession
            }
            MessageVisibilityScope.MEDIA_GALLERY -> {
                // 媒体中心：若该会话有锁，则必须已完成运行时解锁；密聊会话需在自身已进入上下文下方可展示
                !context.isChatLocked || context.isChatUnlockedInSession
            }
        }
    }
}
