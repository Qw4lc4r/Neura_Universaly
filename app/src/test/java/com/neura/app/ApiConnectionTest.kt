package com.neura.app

import org.junit.Assert.*
import org.junit.Test

class ApiConnectionTest {
    @Test fun normalizesRequestUrlsWithoutGuessingVersion() {
        assertEquals("https://router.test/api/v1", ApiConnection.baseUrl(" https://router.test/api/v1/chat/completions/ "))
        assertEquals("https://router.test/custom", ApiConnection.baseUrl("https://router.test/custom/models"))
        assertEquals("https://router.test", ApiConnection.baseUrl("https://router.test/"))
    }

    @Test fun rejectsUnsafeOrIncompleteEndpoints() {
        listOf("http://router.test/v1", "router.test", "https://user:pass@router.test/v1",
            "https://router.test/v1?key=secret", "https://router.test/#key", "https://example.com/v1", "")
            .forEach { value -> assertTrue(value, runCatching { ApiConnection.baseUrl(value) }.isFailure) }
    }

    @Test fun isolatesCachesByKeyAndProtocol() {
        val base = "https://router.test/v1"
        assertNotEquals(ApiConnection.identity("a", base, "one"), ApiConnection.identity("a", base, "two"))
        assertNotEquals(ApiConnection.identity("a", base), ApiConnection.identity("b", base))
        assertEquals(ApiConnection.identity("a", base), ApiConnection.identity("a", "$base/models"))
    }

    @Test fun customProfilesRetainTheirProtocol() {
        assertEquals(ApiProtocols.ANTHROPIC, ApiProtocols.resolve("custom_abc", ApiProtocols.ANTHROPIC))
        assertEquals(ApiProtocols.OPENAI, ApiProtocols.resolve("openai", ApiProtocols.GEMINI))
        assertFalse(ApiProtocols.canRun(ApiProtocols.ANTHROPIC, MediaRoute.IMAGE))
    }

    @Test fun mediaCredentialsRequireTheSameOriginIncludingPort() {
        assertTrue(sameOrigin("https://api.test/file", "https://api.test:443/v1"))
        assertFalse(sameOrigin("https://api.test:8443/file", "https://api.test/v1"))
        assertFalse(sameOrigin("https://cdn.test/file", "https://api.test/v1"))
        assertFalse(sameOrigin("http://api.test/file", "https://api.test/v1"))
    }
}
