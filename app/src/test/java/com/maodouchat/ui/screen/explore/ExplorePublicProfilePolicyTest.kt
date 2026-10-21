package com.maodouchat.ui.screen.explore

import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorePublicProfilePolicyTest {

    @Test
    fun normalizesAtPrefixEncodedAndPath() {
        assertEquals("ada", ExplorePublicProfilePolicy.normalizeUsername("  @ada  "))
        assertEquals("ada", ExplorePublicProfilePolicy.normalizeUsername("%40ada"))
        assertEquals("ada", ExplorePublicProfilePolicy.normalizeUsername("u/ada"))
        assertEquals("ada", ExplorePublicProfilePolicy.normalizeUsername("https://chat.mdou.me/u/ada?x=1"))
        assertEquals("", ExplorePublicProfilePolicy.normalizeUsername("   "))
        assertFalse(ExplorePublicProfilePolicy.isValidUsername(""))
        assertTrue(ExplorePublicProfilePolicy.isValidUsername("ada"))
    }

    @Test
    fun maps404ToNotFoundAndNetworkToLoadFailed() {
        val notFound = ExplorePublicProfilePolicy.displayLoadError(
            failure = ApiException(kind = ApiFailureKind.HTTP, statusCode = 404),
            notFound = "not-found",
            loadFailed = "load-failed",
            networkError = "network"
        )
        assertEquals("not-found", notFound)

        val network = ExplorePublicProfilePolicy.displayLoadError(
            failure = ApiException(kind = ApiFailureKind.NETWORK),
            notFound = "not-found",
            loadFailed = "load-failed",
            networkError = "network"
        )
        assertEquals("load-failed: network", network)

        val timeout = ExplorePublicProfilePolicy.displayLoadError(
            failure = ApiException(kind = ApiFailureKind.TIMEOUT),
            notFound = "not-found",
            loadFailed = "load-failed",
            networkError = "network"
        )
        assertEquals("load-failed: network", timeout)
    }

    @Test
    fun normalizesRepeatedAtPrefixesAndQuotes() {
        assertEquals("ada", ExplorePublicProfilePolicy.normalizeUsername("@@ada"))
        assertEquals("ada", ExplorePublicProfilePolicy.normalizeUsername("'@ada'"))
        assertEquals("ada", ExplorePublicProfilePolicy.normalizeUsername("\"ada\""))
        assertEquals("", ExplorePublicProfilePolicy.normalizeUsername(null))
    }

    @Test
    fun stripsFragmentAndQuery() {
        assertEquals("ada", ExplorePublicProfilePolicy.normalizeUsername("ada#section?x=1"))
        assertEquals("ada", ExplorePublicProfilePolicy.normalizeUsername("u/ada?x=1#frag"))
    }

    @Test
    fun maps410ToNotFound() {
        val result = ExplorePublicProfilePolicy.displayLoadError(
            failure = ApiException(kind = ApiFailureKind.HTTP, statusCode = 410),
            notFound = "not-found",
            loadFailed = "load-failed",
            networkError = "network"
        )
        assertEquals("not-found", result)
    }

    @Test
    fun prefersServerMessageInLoadError() {
        val server = ExplorePublicProfilePolicy.displayLoadError(
            failure = ApiException(kind = ApiFailureKind.HTTP, statusCode = 500, serverMessage = "banned"),
            notFound = "not-found",
            loadFailed = "load-failed",
            networkError = "network"
        )
        assertEquals("load-failed: banned", server)

        val plain = ExplorePublicProfilePolicy.displayLoadError(
            failure = RuntimeException("boom"),
            notFound = "not-found",
            loadFailed = "load-failed",
            networkError = "network"
        )
        assertEquals("load-failed: boom", plain)
    }

    @Test
    fun blankErrorDetailsFallBackToLoadFailed() {
        val blank = ExplorePublicProfilePolicy.displayLoadError(
            failure = RuntimeException(),
            notFound = "not-found",
            loadFailed = "load-failed",
            networkError = "network"
        )
        assertEquals("load-failed", blank)
    }
}
