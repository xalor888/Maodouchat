package com.maodouchat.domain.messaging

/** 信封 kind 线上词汇：持久化在 Room 收件箱行、服务端同值约定，改值会断已落库数据与服务端约定。 */
object EnvelopeKind {
    const val SENDER_KEY = "SENDER_KEY"
    const val SERVICE = "SERVICE"
}
