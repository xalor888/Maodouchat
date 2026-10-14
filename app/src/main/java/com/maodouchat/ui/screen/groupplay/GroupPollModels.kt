package com.maodouchat.ui.screen.groupplay

/** 群投票列表项（服务端 PollSnapshot 的轻量客户端模型）。 */
data class PollListItem(
    val id: String,
    val question: String,
    val options: List<String>,
    val multi: Boolean,
    val anonymous: Boolean,
    val closed: Boolean,
    val counts: List<Int>,
    val totalVoters: Int,
    val myVotes: List<Int>
)
