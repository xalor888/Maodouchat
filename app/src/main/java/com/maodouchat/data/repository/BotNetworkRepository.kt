package com.maodouchat.data.repository

import com.maodouchat.network.ApiService
import com.maodouchat.network.ChatDto

/**
 * 机器人管理的**远端**调用（G328c）。
 *
 * 覆盖开发者中心里那一屏：创建/删除、列表、令牌重生成、启用开关、Webhook、开一个与机器人的直聊。
 * 注意 `listBots` 返回的是**服务端原始 JSON 文本**（历史接口就是这样），
 * 调用方自己解析——所以这里不做「顺手解析成对象」的改造：那会改变错误处理的位置
 * （解析失败从「调用方的一条提示」变成「仓库层的异常」），不属于搬移该做的事。
 *
 * 刻意很薄（不缓存、不重试）：机器人令牌与启用状态必须有服务端权威视图。
 */
internal class BotNetworkRepository(
    private val listBotsApi: suspend (String) -> Result<String> = { token -> ApiService.listBots(token) },
    private val createBotApi: suspend (String, String, String, String?) -> Result<String> =
        { token, name, username, description -> ApiService.createBot(token, name, username, description) },
    private val deleteBotApi: suspend (String, String) -> Result<String> =
        { token, botId -> ApiService.deleteBot(token, botId) },
    private val regenerateTokenApi: suspend (String, String) -> Result<String> =
        { token, botId -> ApiService.regenerateBotToken(token, botId) },
    private val setEnabledApi: suspend (String, String, Boolean) -> Result<String> =
        { token, botId, enabled -> ApiService.setBotEnabled(token, botId, enabled) },
    private val setWebhookApi: suspend (String, String, String?) -> Result<String> =
        { token, botId, url -> ApiService.setBotWebhook(token, botId, url) },
    private val openDirectChatApi: suspend (String, String) -> Result<ChatDto> =
        { token, botId -> ApiService.openBotDirectChat(token, botId) },
    private val inviteToChatApi: suspend (String, String, String) -> Result<String> =
        { token, chatId, botId -> ApiService.inviteBotToChat(token, chatId, botId) },
    private val chatCommandsApi: suspend (String, String) -> Result<String> =
        { token, chatId -> ApiService.listChatBotCommands(token, chatId) },
    private val postInboxApi: suspend (String, String, String, String?) -> Result<String> =
        { token, chatId, text, botId -> ApiService.postBotInbox(token, chatId, text, botId) },
) {
    /** 服务端原始列表 JSON（调用方自行解析，见类注释）。 */
    suspend fun listBots(token: String): Result<String> = listBotsApi(token)

    suspend fun createBot(token: String, name: String, username: String, description: String? = null): Result<String> =
        createBotApi(token, name, username, description)

    suspend fun deleteBot(token: String, botId: String): Result<String> = deleteBotApi(token, botId)

    suspend fun regenerateToken(token: String, botId: String): Result<String> = regenerateTokenApi(token, botId)

    suspend fun setEnabled(token: String, botId: String, enabled: Boolean): Result<String> =
        setEnabledApi(token, botId, enabled)

    suspend fun setWebhook(token: String, botId: String, url: String?): Result<String> =
        setWebhookApi(token, botId, url)

    suspend fun openDirectChat(token: String, botId: String): Result<ChatDto> = openDirectChatApi(token, botId)

    /** 把机器人邀请进某个会话。 */
    suspend fun inviteToChat(token: String, chatId: String, botId: String): Result<String> =
        inviteToChatApi(token, chatId, botId)

    /** 某会话内机器人的可用指令（服务端原始 JSON 文本）。 */
    suspend fun chatCommands(token: String, chatId: String): Result<String> = chatCommandsApi(token, chatId)

    /** 往机器人收件箱投递（`/` 指令或自由文本）。 */
    suspend fun postInbox(token: String, chatId: String, text: String, botId: String? = null): Result<String> =
        postInboxApi(token, chatId, text, botId)
}
