package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.AdminExportRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** 设备事件一致性查询。 */
internal fun Route.configureAdminDeviceConsistencyRoutes(
    exportRepository: AdminExportRepository,
) {
    authenticate("admin-jwt") {
        route("/api/admin") {
            get("/device-consistency/summary") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val userId = parseOptionalTrimmed(call.request.queryParameters, "userId")
                val sequences = exportRepository.deviceSequences(userId).map { row ->
                    DeviceSeqResponse(
                        userId = row.userId,
                        deviceId = row.deviceId,
                        eventType = row.eventType,
                        lastAppliedSeq = row.lastAppliedSeq,
                        lastEventAt = row.lastEventAt,
                    )
                }
                val anomalyCount = exportRepository.deviceAnomalyCount(userId)
                call.respond(DeviceConsistencySummaryResponse(sequences = sequences, anomalyCount = anomalyCount))
            }

            get("/device-consistency/events") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val limit = parseAdminListLimit(call.request.queryParameters)
                val offset = parseAdminListOffset(call.request.queryParameters)
                val status = parseUpperToken(call.request.queryParameters, "status", 20)
                val userId = parseOptionalTrimmed(call.request.queryParameters, "userId")
                val events = exportRepository.deviceAnomalies(userId, status, limit, offset).map { row ->
                    DeviceAnomalyResponse(
                        id = row.id,
                        userId = row.userId,
                        deviceId = row.deviceId,
                        eventType = row.eventType,
                        seq = row.seq,
                        status = row.status,
                        referenceId = row.referenceId,
                        firstSeenAt = row.firstSeenAt,
                        lastSeenAt = row.lastSeenAt,
                        detail = row.detail,
                    )
                }
                call.respond(events)
            }
        }
    }
}

@Serializable
data class DeviceSeqResponse(
    val userId: String,
    val deviceId: Int,
    val eventType: String,
    val lastAppliedSeq: Long,
    val lastEventAt: Long
)

@Serializable
data class DeviceConsistencySummaryResponse(
    val sequences: List<DeviceSeqResponse>,
    val anomalyCount: Long
)

@Serializable
data class DeviceAnomalyResponse(
    val id: String,
    val userId: String,
    val deviceId: Int,
    val eventType: String,
    val seq: Long,
    val status: String,
    val referenceId: String?,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val detail: String?
)
