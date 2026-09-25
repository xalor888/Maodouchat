package com.maodouchat.data.repository

import com.maodouchat.network.ApiService

/**
 * 群投票的三个端点的**远端**调用（G328c）。
 *
 * 一开始把 `voteGroupPoll` 塞进了 `GroupNetworkRepository`（因为它也是群功能），
 * 随后按**失败语义**归位到这里：投票的失败是「投票已关闭 / 已投过 / 选项越界」，
 * 与「加人/改名」那组的失败（无权操作、成员已存在）完全不同，混在一起调用方就得
 * 在一处 catch 里分辨两套错误。`GroupNetworkRepository` 只管成员与群资料。
 *
 * 三个端点返回的都是**服务端渲染好的原始 JSON 文本**（历史接口就这样），
 * 本类不做解析——解析属于展示层的格式化，塞进仓库会让「原文」这个信息被悄悄丢掉。
 *
 * 刻意很薄（不缓存、不重试）：投票结果必须实时。
 */
internal class GroupPollNetworkRepository(
    private val createPollApi: suspend (String, String, String, List<String>, Boolean, Boolean) -> Result<String> =
        { token, chatId, question, options, multi, anonymous ->
            ApiService.createGroupPoll(token, chatId, question, options, multi, anonymous)
        },
    private val fetchPollApi: suspend (String, String) -> Result<String> =
        { token, pollId -> ApiService.getGroupPoll(token, pollId) },
    private val votePollApi: suspend (String, String, List<Int>) -> Result<String> =
        { token, pollId, optionIndexes -> ApiService.voteGroupPoll(token, pollId, optionIndexes) },
) {
    /** 建投票。`multi`/`anonymous` 必须显式给，不做「智能默认」——建错了改不回来。 */
    suspend fun createPoll(
        token: String,
        chatId: String,
        question: String,
        options: List<String>,
        multi: Boolean = false,
        anonymous: Boolean = false,
    ): Result<String> = createPollApi(token, chatId, question, options, multi, anonymous)

    /** 拉单个投票的原始 JSON（供展示层渲染当前票数）。 */
    suspend fun fetchPoll(token: String? = null, pollId: String): Result<String> =
        fetchPollApi(token ?: currentAccessToken(), pollId)

    /** 投票。`optionIndexes` 是**下标**列表，不是选项 id。 */
    suspend fun votePoll(token: String, pollId: String, optionIndexes: List<Int>): Result<String> =
        votePollApi(token, pollId, optionIndexes)
}
