package com.maodouchat.server.plugins

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouteRegistrySplitTest {
    private fun source(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/maodouchat/server/plugins/$name"),
            File("server/src/main/kotlin/com/maodouchat/server/plugins/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("cannot locate $name from ${File(".").absolutePath}")
    }

    @Test
    fun `Bot API registry delegates without owning endpoints`() {
        val registry = source("BotApiRouting.kt")
        val core = source("BotCoreRouting.kt")
        val presentation = source("BotPresentationRouting.kt")

        assertTrue(registry.lineSequence().count() <= 50)
        assertTrue("configureBotCoreRoutes(" in registry)
        assertTrue("configureBotPresentationRoutes(" in core)
        assertFalse(ENDPOINT_DECLARATION.containsMatchIn(registry))
        assertEquals(961, ENDPOINT_DECLARATION.findAll(core + presentation).count())
    }

    @Test
    fun `admin registry delegates and observability remains registered`() {
        val registry = source("AdminRouting.kt")
        val management = source("AdminManagementRouting.kt")
        val observability = source("AdminObservabilityRouting.kt")

        assertTrue(registry.lineSequence().count() <= 30)
        assertTrue("configureAdminManagementRouting(" in registry)
        assertTrue("configureAdminObservabilityRoutes(ServerConfig)" in management)
        assertFalse(ENDPOINT_DECLARATION.containsMatchIn(registry))
        assertEquals(387, ENDPOINT_DECLARATION.findAll(management + observability).count())
        listOf(
            "channel-health",
            "dashboard",
            "system-stats",
            "trends",
            "online",
            "ranking",
            "storage",
            "rich-trends",
            "audit-logs",
        ).forEach { path -> assertTrue("get(\"/$path" in observability, path) }
    }

    @Test
    fun `primary Bot sender uses atomic service publishing`() {
        val core = source("BotCoreRouting.kt")
        val sender = core.substringAfter("post(\"/api/bot/sendMessage\")")
            .substringBefore("get(\"/api/bot/getMyCommands\")")

        assertTrue("publishBotServiceMessage(" in sender)
        assertFalse("serviceMessageRepo.insert(" in sender)
        assertFalse("fanoutBotMessage(" in sender)
    }

    @Test
    fun `primary registry delegates public update and profile routes`() {
        val registry = source("Routing.kt")
        val update = source("PublicUpdateRouting.kt")
        val profile = source("PublicProfileRouting.kt")

        assertTrue("configurePublicUpdateRoutes(cacheService)" in registry)
        assertTrue("configurePublicProfileRoutes(" in registry)
        listOf(
            "/api/public/updates",
            "/api/public/app-update/latest.apk",
            "/api/internal/app-update",
            "/api/public/status",
            "/api/webrtc/lib/{abi}",
            "/api/public/profile/{username}",
            "/u/{username}",
        ).forEach { path ->
            val declaration = Regex("(?m)^\\s*(get|post|put|delete|patch)\\(\\\"${Regex.escape(path)}\\\"")
            assertFalse(declaration.containsMatchIn(registry), path)
            assertTrue(declaration.containsMatchIn(update) || declaration.containsMatchIn(profile), path)
        }
    }

    private companion object {
        val ENDPOINT_DECLARATION = Regex("(?m)^\\s*(get|post|put|delete|patch)\\(\\\"")
    }
}
