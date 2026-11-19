package com.neura.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RouterPresetsTest {
    private fun request(id: String, model: String = "test", web: Boolean = false, thinking: Boolean = true): JSONObject =
        JSONObject().also { RouterFeatures.apply(it, ApiProtocols.OPENAI_COMPAT, web, thinking, "https://custom.test/v1", model, id) }

    @Test fun includesOneKeyAndTenDistinctAdditionalServices() {
        assertEquals(11, RouterPresets.all.size)
        assertEquals(11, RouterPresets.all.map { it.id }.distinct().size)
        assertEquals("https://api.onekey-ai.lol/v1", Providers.endpoint("onekey"))
        assertEquals(ApiProtocols.RESPONSES, ApiProtocols.defaultFor("onekey"))
    }
    @Test fun detectionRequiresExactHostAndNeverMatchesLookalikes() {
        assertEquals("onekey", RouterPresets.detect("https://api.onekey-ai.lol/v1")?.id)
        assertNull(RouterPresets.detect("https://api.onekey-ai.lol.evil.test/v1"))
        assertNull(RouterPresets.detect("https://evil.test/api.onekey-ai.lol"))
        assertEquals("together", RouterPresets.detect("https://api.together.xyz/v1")?.id)
        assertNull(RouterPresets.resolve("generic", "https://api.groq.com/openai/v1"))
    }
    @Test fun oneKeyRoutesClaudeToMessagesAndGptToResponses() {
        assertEquals(ApiProtocols.ANTHROPIC, RouterPresets.protocol(ApiProtocols.RESPONSES, "onekey", "https://custom.test/v1", "claude-sonnet-4-6"))
        assertEquals(ApiProtocols.RESPONSES, RouterPresets.protocol(ApiProtocols.OPENAI_COMPAT, "onekey", "https://custom.test/v1", "gpt-5"))
        assertEquals(ApiProtocols.OPENAI_COMPAT, RouterPresets.protocol(ApiProtocols.OPENAI_COMPAT, "generic", "https://api.onekey-ai.lol/v1", "gpt-5"))
    }
    @Test fun togetherUsesModelSpecificReasoningControl() {
        assertTrue(request("together").getJSONObject("reasoning").getBoolean("enabled"))
        assertEquals("medium", request("together", "openai/gpt-oss-120b").getString("reasoning_effort"))
    }
    @Test fun vercelSearchIsAServerTool() {
        assertEquals("vercel:perplexity_search", request("vercel", web = true).getJSONArray("tools").getJSONObject(0).getString("type"))
    }
    @Test fun groqSearchChecksModelAndUsesNativeTool() {
        assertTrue(runCatching { request("groq", web = true) }.isFailure)
        assertEquals("browser_search", request("groq", "openai/gpt-oss-120b", web = true).getJSONArray("tools").getJSONObject(0).getString("type"))
    }
    @Test fun doesNotInventSearchParametersForInferenceOnlyAdapters() {
        listOf("together", "fireworks", "deepinfra", "huggingface", "novita", "siliconflow").forEach { id ->
            assertTrue(id, runCatching { request(id, web = true) }.exceptionOrNull()?.message?.contains("поиск") == true)
        }
    }
    @Test fun profilesDoNotSendReasoningControlsWhenNotRequested() {
        RouterPresets.all.filter { it.id != "onekey" }.forEach { preset ->
            assertEquals(preset.id, 0, request(preset.id, thinking = false).length())
        }
    }
}
