package com.neura.app

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class ChatToolsRegressionTest {
    @Test fun reasoningOffIsExplicitForOpenRouterAndKnownDeepSeekModels() {
        val openRouter = JSONObject()
        RouterFeatures.apply(openRouter, ApiProtocols.OPENROUTER, web = false, reasoning = false,
            capability = RouterReasoning(available = true))
        assertFalse(openRouter.getJSONObject("reasoning").getBoolean("enabled"))
        val deepSeek = JSONObject()
        RouterFeatures.apply(deepSeek, ApiProtocols.OPENAI_COMPAT, web = false, reasoning = false,
            endpoint = "https://api.deepseek.com/v1", model = "deepseek-v4-pro")
        assertEquals("disabled", deepSeek.getJSONObject("thinking").getString("type"))
    }

    @Test fun customRouterUsesSelectedReasoningFormatInBothModes() {
        listOf(true, false).forEach { enabled ->
            val payload = JSONObject()
            RouterFeatures.apply(payload, ApiProtocols.OPENAI_COMPAT, web = false, reasoning = enabled,
                endpoint = "https://router.example/v1", format = ReasoningFormat.TEMPLATE)
            assertEquals(enabled, payload.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
            assertFalse(payload.has("reasoning_effort"))
        }
    }

    @Test fun splitThinkTagsDoNotAppearInAnswer() = runBlocking {
        val answer = StringBuilder()
        val thought = StringBuilder()
        val stream = ThoughtStream({ answer.append(it) }, { thought.append(it) })
        listOf(" <th", "ink>Расс", "уждение</th", "ink>Готово").forEach { stream.accept(it) }
        stream.finish()
        assertEquals("Готово", answer.toString())
        assertEquals("Рассуждение", thought.toString())
    }

    @Test fun tableKeepsEscapedPipesAndBlankCells() {
        assertEquals(listOf("`a|b`", "", "**текст**"), splitMarkdownCells("| `a\\|b` | | **текст** |"))
    }

    @Test fun browserSkipsPrivateAndNonGlobalAddresses() {
        listOf("127.0.0.1", "169.254.169.254", "192.168.1.2", "100.64.0.1", "::1", "fc00::1", "2002:7f00:1::")
            .forEach { assertFalse(it, WebContext.isPublicAddress(InetAddress.getByName(it))) }
        listOf("8.8.8.8", "2606:4700:4700::1111")
            .forEach { assertTrue(it, WebContext.isPublicAddress(InetAddress.getByName(it))) }
    }

    @Test fun balanceUsesTheSpecifiedNumericFieldWithoutGuessing() {
        val response = JSONObject("""{"data":{"remaining":"17.20","used":4}}""")
        assertEquals("17.2", RouterBalance.remaining(response, "data.remaining"))
        assertTrue(runCatching { RouterBalance.remaining(response, "remaining") }.isFailure)
        assertTrue(runCatching { RouterBalance.remaining(JSONObject("""{"n":"1e100000"}"""), "n") }.isFailure)
    }
}
