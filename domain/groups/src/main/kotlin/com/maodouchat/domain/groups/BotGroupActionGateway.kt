package com.maodouchat.domain.groups

import com.maodouchat.core.model.UserDto

/** 机器人群操作（群改名/加人/踢人/候选人加载、机器人收件箱/指令/邀请）的网络端口。app 侧直通 GroupNetworkRepository / BotNetworkRepository。 */
interface BotGroupActionGateway {
    suspend fun allSearchableUsers(token: String): Result<List<UserDto>>
    suspend fun renameGroup(token: String, chatId: String, name: String): Result<Unit>
    suspend fun addMembers(token: String, chatId: String, userIds: List<String>): Result<Unit>
    suspend fun removeMember(token: String, chatId: String, userId: String): Result<Unit>
    suspend fun postBotInbox(token: String, chatId: String, plaintext: String): Result<String>
    suspend fun chatCommands(token: String, chatId: String): Result<String>
    suspend fun listBots(token: String): Result<String>
    suspend fun inviteBotToChat(token: String, chatId: String, botId: String): Result<String>
}
