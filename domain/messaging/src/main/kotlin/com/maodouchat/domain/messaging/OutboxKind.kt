package com.maodouchat.domain.messaging

/** 出件箱 kind 线上词汇：持久化在 Room 出件箱行、群控过期策略按值比对，改值会断已落库数据与服务端约定。 */
object OutboxKind {
    const val DATA = "DATA"
    const val EVENT = "EVENT"
    const val RECEIPT = "RECEIPT"
    const val SENDER_KEY = "SENDER_KEY"
    const val KEY_REQUEST = "KEY_REQUEST"
}
