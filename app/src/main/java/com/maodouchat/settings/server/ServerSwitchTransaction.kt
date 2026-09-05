package com.maodouchat.settings.server

import android.content.Context
import com.maodouchat.network.ApiConfig
import com.maodouchat.network.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

sealed interface ServerSwitchResult {
    data class Success(val oldUrl: String, val newUrl: String) : ServerSwitchResult
    data object Unchanged : ServerSwitchResult
    data class Failure(val reason: String, val rolledBack: Boolean) : ServerSwitchResult
}

/**
 * Transaction coordinator ensuring safe, atomic switching between backend server hosts.
 * Protects against credential replay attacks and cross-domain token leaks.
 */
class ServerSwitchTransaction(
    private val context: Context,
) {
    /**
     * Executes server switch with transactional isolation and token purging.
     */
    suspend fun executeSwitch(targetUrl: String): ServerSwitchResult = withContext(Dispatchers.IO) {
        val trimmed = targetUrl.trim()
        val validationError = validateServerUrl(trimmed)
        if (validationError != null) {
            return@withContext ServerSwitchResult.Failure(validationError, rolledBack = false)
        }

        val normalized = normalizeUrl(trimmed)
        val currentBaseUrl = ApiConfig.BASE_URL

        if (normalized.equals(currentBaseUrl.trimEnd('/'), ignoreCase = true)) {
            return@withContext ServerSwitchResult.Unchanged
        }

        // 1. Transaction checkpoint: safely purge old credentials BEFORE switching domains
        val tokenManager = TokenManager.getInstance(context)
        val previousToken = tokenManager.getToken()
        val previousUserId = tokenManager.getUserId()

        try {
            // Purge credentials to prevent sending old tokens to new server
            tokenManager.clear()

            // 2. Switch runtime endpoint via ApiConfig
            val changeResult = ApiConfig.switchServer(normalized, context)
            when (changeResult) {
                is ApiConfig.ServerChangeResult.Changed -> {
                    ServerSwitchResult.Success(oldUrl = currentBaseUrl, newUrl = normalized)
                }
                is ApiConfig.ServerChangeResult.Unchanged -> {
                    ServerSwitchResult.Unchanged
                }
                is ApiConfig.ServerChangeResult.Failed -> {
                    // Rollback token if server switch failed
                    if (previousToken != null) {
                        tokenManager.saveToken(previousToken)
                    }
                    if (previousUserId != null) {
                        tokenManager.saveUserId(previousUserId)
                    }
                    ServerSwitchResult.Failure(changeResult.message, rolledBack = true)
                }
            }
        } catch (e: Exception) {
            // Exception safety rollback
            if (previousToken != null) {
                tokenManager.saveToken(previousToken)
            }
            if (previousUserId != null) {
                tokenManager.saveUserId(previousUserId)
            }
            ServerSwitchResult.Failure(e.message ?: "Unknown server switch error", rolledBack = true)
        }
    }

    companion object {
        fun validateServerUrl(url: String): String? {
            if (url.isBlank()) return "Server address cannot be empty"
            return try {
                val uri = URI(url)
                val scheme = uri.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    return "Only http:// or https:// addresses are supported"
                }
                if (uri.host.isNullOrBlank()) {
                    return "Invalid host in server address"
                }
                null
            } catch (e: Exception) {
                "Malformed URL: ${e.message}"
            }
        }

        fun normalizeUrl(url: String): String {
            return url.trim().trimEnd('/')
        }
    }
}
