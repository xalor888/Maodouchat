package com.maodouchat.core.model

/** 剧透媒体的类型策略：能设剧透与能阅后即焚是两个独立的产品决策。 */
object SpoilerMediaPolicy {
    // 用不可变包装暴露：JVM 上 setOf 的运行时类型仍是可变的 LinkedHashSet，
    // 不包一层的话外部 (as MutableSet).add 就能污染这个单例。
    val SUPPORTED: Set<MessageType> = java.util.Collections.unmodifiableSet(
        setOf(
            MessageType.IMAGE,
            MessageType.VIDEO,
            MessageType.GIF
        )
    )

    /** true 表示该消息类型可以在发送前开启剧透模糊。 */
    fun supports(type: MessageType): Boolean = type in SUPPORTED
}
