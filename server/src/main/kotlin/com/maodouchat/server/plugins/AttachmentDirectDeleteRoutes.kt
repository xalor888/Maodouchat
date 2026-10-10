package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.BlobStore
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** 附件删除：删除待确认附件（DELETE /api/attachments/{id}）。 */
internal fun Route.configureAttachmentDirectDeleteRoutes(
    encryptedAttachmentRepo: EncryptedAttachmentRepository,
) {
    authenticate("auth-jwt") {



                    delete("/api/attachments/{id}") {
                        val userId = call.requireUserId()
                        val attachmentId = parseRawOrEmpty(call.parameters, "id")
                        if (!encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)) {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse("待确认附件不存在"))
                            return@delete
                        }
                        BlobStore.delete(attachmentId)
                        call.respond(
                        buildJsonObject {
        put("status", "ok")
                        }
                    )
                    }
    }
}
