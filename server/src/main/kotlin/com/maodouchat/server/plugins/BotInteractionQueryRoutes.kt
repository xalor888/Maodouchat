package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.ConversationParticipantRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Bot 交互·查询：会话内可用 bot 命令/菜单。 */
internal fun Route.configureBotInteractionQueryRoutes(
    conversationParticipantRepo: ConversationParticipantRepository,
) {

        get("/api/chats/{chatId}/bot-commands") {
            val userId = call.requireUserId()
            val chatId = call.requirePathParamOr400("chatId", "缺少聊天 ID") ?: return@get
            if (!conversationParticipantRepo.isParticipant(chatId, userId)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该聊天"))
                return@get
            }
            call.respond(
                buildJsonObject {
                    put("ok", true)
                    putJsonArray("bots") {
                        com.maodouchat.server.repository.BotRepository.listEnabledBotsInChat(chatId).forEach { bot ->
                            add(
                                buildJsonObject {
                                    put("id", bot.id)
                                    put("username", bot.username)
                                    put("name", bot.name)
                                }
                            )
                        }
                    }
                    putJsonArray("commands") {
                        com.maodouchat.server.repository.BotRepository.listCommandsForChat(chatId).forEach { item ->
                            add(
                                buildJsonObject {
                                    put("botId", item.botId)
                                    put("username", item.username)
                                    put("name", item.name)
                                    put("command", item.command)
                                    put("description", item.description)
                                }
                            )
                        }
                    }
                }
            )
        }
}
