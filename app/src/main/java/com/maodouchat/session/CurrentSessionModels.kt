package com.maodouchat.session

/** 某一时刻的会话快照。两个值要么都有效，要么都是 null（无会话）。 */
data class Snapshot(val token: String?, val userId: String?)
