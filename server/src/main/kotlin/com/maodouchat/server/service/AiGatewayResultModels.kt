package com.maodouchat.server.service

data object NotConfigured : AiGatewayResult<Nothing>
data class UpstreamError(
    val statusCode: Int,
    val message: String?,
    val retryAfterSeconds: Long? = null
) : AiGatewayResult<Nothing>
data class InvalidResponse(val message: String) : AiGatewayResult<Nothing>
