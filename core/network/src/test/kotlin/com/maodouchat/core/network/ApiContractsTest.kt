package com.maodouchat.core.network

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分域 API 契约随 Api*Contracts.kt 由 app 侧迁入 :core:network。
 * 接口本身无行为可测；这里钉住迁移的结构性事实：12 个契约接口都在
 * com.maodouchat.core.network 包内，且 MediaApi 仍聚合三个媒体子接口。
 */
class ApiContractsTest {

    @Test
    fun `moved api contracts are interfaces in core network`() {
        val contracts = listOf(
            AuthApi::class,
            MessagingApi::class,
            ConversationApi::class,
            GroupApi::class,
            KeyDeviceApi::class,
            CallSignalingApi::class,
            MediaUploadApi::class,
            MediaDownloadApi::class,
            MediaImageApi::class,
            MediaApi::class,
            SocialApi::class,
            ApiSurface::class,
        )
        for (contract in contracts) {
            assertTrue(
                contract.qualifiedName ?: contract.simpleName ?: "?",
                contract.java.isInterface,
            )
        }
    }

    @Test
    fun `ApiSurface extends SocialApi`() {
        val supertypes = ApiSurface::class.java.interfaces
            .map { it.simpleName }.toSet()
        assertTrue(supertypes.contains("SocialApi"))
    }

    @Test
    fun `MediaApi composes the three media sub-apis`() {
        val supertypes = MediaApi::class.java.interfaces
            .map { it.simpleName }.toSet()
        assertTrue(
            supertypes.containsAll(
                setOf("MediaUploadApi", "MediaDownloadApi", "MediaImageApi")
            )
        )
    }
}
