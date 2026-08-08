package com.maodouchat.server.plugins

import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.AuthTokenRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*

fun Application.configureAuthentication() {
    val authTokenRepo = AuthTokenRepository()
    install(Authentication) {
        jwt("auth-jwt") {
            verifier(JwtConfig.verifier)
            validate { credential ->
                val userId = credential.payload.subject
                if (
                    userId != null &&
                    authTokenRepo.isAccessTokenAllowed(
                        userId = userId,
                        tokenVersion = JwtConfig.tokenVersion(credential.payload),
                        tokenId = credential.payload.id,
                        authSessionId = JwtConfig.authSessionId(credential.payload),
                        requireAuthSession = true
                    )
                ) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Token 无效或已过期"))
            }
        }
        jwt("admin-jwt") {
            verifier(JwtConfig.verifier)
            validate { credential ->
                val userId = credential.payload.subject
                if (
                    userId != null &&
                    JwtConfig.isAdminSession(credential.payload) &&
                    authTokenRepo.isAccessTokenAllowed(
                        userId = userId,
                        tokenVersion = JwtConfig.tokenVersion(credential.payload),
                        tokenId = credential.payload.id
                    )
                ) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("管理员会话无效或已过期"))
            }
        }
    }
}
