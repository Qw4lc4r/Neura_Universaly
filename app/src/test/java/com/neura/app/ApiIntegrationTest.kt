package com.neura.app

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

class ApiIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost")
        .addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("::1").build()
    private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    private val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
        .followRedirects(false).build()
    private fun server() = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
    private fun event(json: String) = "data: $json\n\n"
    private fun MockWebServer.reply(body: String, json: Boolean = false) = enqueue(MockResponse()
        .setHeader("Content-Type", if (json) "application/json" else "text/event-stream").setBody(body))
    private suspend fun chat(server: MockWebServer, protocol: String = ApiProtocols.OPENAI_COMPAT,
        reasoning: Boolean = false, web: Boolean = false, onReasoning: suspend (String) -> Unit = {},
        routerProfile: String = "auto", model: String = "test-model"): String {
        var text = ""
        UniversalApi(client).stream(mock(Context::class.java), protocol, server.url("/v1/chat/completions").toString(),
            "test-key", model, "Be helpful", web, reasoning, 4096, 1.0,
            listOf(Message(role = "user", text = "hello")), false, false,
            onText = { text += it }, onImage = { fail("Unexpected image") }, onReasoning = onReasoning, routerProfile = routerProfile)
        return text
    }

    @Test fun discoversEveryGeminiPageAndUsesApiKeyHeader() = runBlocking {
        server().use { s ->
            s.reply("""{"models":[{"name":"models/one"}],"nextPageToken":"a+b"}""", true)
            s.reply("""{"models":[{"name":"models/two"}]}""", true)
            val result = ModelCatalog(temporary.root, client).list("custom_test", s.url("/v1beta").toString(), "test-key", ApiProtocols.GEMINI)
            assertEquals(listOf("one", "two"), result.models.map { it.id })
            assertEquals("test-key", s.takeRequest().getHeader("x-goog-api-key"))
            assertEquals("/v1beta/models?pageToken=a%2Bb", s.takeRequest().path)
        }
    }
    @Test fun discoversEveryAnthropicPage() = runBlocking {
        server().use { s ->
            s.reply("""{"data":[{"id":"one"}],"has_more":true,"last_id":"one"}""", true)
            s.reply("""{"data":[{"id":"two"}],"has_more":false}""", true)
            assertEquals(2, ModelCatalog(temporary.root, client).list("custom_test", s.url("/v1").toString(), "test-key", ApiProtocols.ANTHROPIC).models.size)
            assertEquals("test-key", s.takeRequest().getHeader("x-api-key"))
            assertEquals("/v1/models?after_id=one", s.takeRequest().path)
        }
    }
    @Test fun invalidKeyCannotMasqueradeAsSuccessfulCatalog() = runBlocking {
        server().use { s ->
            s.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"invalid key"}}"""))
            val error = runCatching { ModelCatalog(temporary.root, client).list("custom", s.url("/v1").toString(), "wrong") }.exceptionOrNull()
            assertTrue(error is ApiHttpException)
            assertEquals(401, (error as ApiHttpException).status)
        }
    }
    @Test fun budgetRejectionRetriesOnceWithOnlyTheOutputCapReduced() = runBlocking {
        server().use { s ->
            s.enqueue(MockResponse().setResponseCode(402).setBody(
                """{"error":{"message":"Request needs fewer max_tokens. You requested up to 4096 tokens, but can only afford 1024.","metadata":{"limit_source":"openrouter_key_limit"}}}"""
            ))
            s.reply(event("""{"choices":[{"delta":{"content":"hello"}}]}""") + event("[DONE]"))
            assertEquals("hello", chat(s, ApiProtocols.OPENROUTER))
            val first = s.takeRequest()
            val second = s.takeRequest()
            val original = JSONObject(first.body.readUtf8())
            val retried = JSONObject(second.body.readUtf8())
            assertEquals(4096, original.getInt("max_tokens"))
            assertEquals(1024, retried.getInt("max_tokens"))
            original.put("max_tokens", 1024)
            val fields = original.keys().asSequence().toSet()
            assertEquals(fields, retried.keys().asSequence().toSet())
            fields.forEach { assertEquals(original.get(it).toString(), retried.get(it).toString()) }
            assertEquals(first.path, second.path)
            assertEquals(first.getHeader("Authorization"), second.getHeader("Authorization"))
            assertEquals(2, s.requestCount)
        }
    }
    @Test fun unresolvedPaymentErrorShowsServerDetailWithoutLeakingTheKey() = runBlocking {
        server().use { s ->
            s.enqueue(MockResponse().setResponseCode(402).setHeader("X-Request-ID", "req-42")
                .setBody("""{"error":"Selected model is excluded from this plan (test-key)"}"""))
            s.enqueue(MockResponse().setResponseCode(500))
            val failure = runCatching { chat(s) }.exceptionOrNull() as ApiHttpException
            assertEquals(402, failure.status)
            assertTrue(failure.message.orEmpty().contains("Selected model is excluded"))
            assertTrue(failure.message.orEmpty().contains("req-42"))
            assertFalse(failure.message.orEmpty().contains("test-key"))
            assertEquals(1, s.requestCount)
        }
    }
    @Test fun paymentErrorInsideStreamIsNeverRetried() = runBlocking {
        server().use { s ->
            s.reply(event("""{"choices":[{"delta":{"content":"partial"}}]}""") + event(
                """{"error":{"code":402,"message":"Request needs fewer max_tokens; can only afford 1024.","metadata":{"provider_name":"Example","raw":"{\"error\":{\"message\":\"Model provider requires a paid plan\"}}"}}}"""
            ))
            s.enqueue(MockResponse().setResponseCode(500))
            val failure = runCatching { chat(s) }.exceptionOrNull() as ApiHttpException
            assertEquals(402, failure.status)
            assertTrue(failure.message.orEmpty().contains("Example · Model provider requires a paid plan"))
            assertEquals(1, s.requestCount)
        }
    }
    @Test fun streamsWhitespaceAndIgnoresNullContent() = runBlocking {
        server().use { s ->
            s.reply(listOf("null", "\"hello\"", "\" \"", "\"world\"").joinToString("") { event("""{"choices":[{"delta":{"content":$it}}]}""") } + event("[DONE]"))
            assertEquals("hello world", chat(s))
            val r = s.takeRequest()
            assertEquals("/v1/chat/completions", r.path)
            assertEquals("Bearer test-key", r.getHeader("Authorization"))
            assertEquals("test-model", JSONObject(r.body.readUtf8()).getString("model"))
        }
    }
    @Test fun acceptsCompatibleServersReturningJsonInsteadOfSse() = runBlocking {
        server().use { s ->
            s.reply("""{"choices":[{"message":{"role":"assistant","content":"hello"}}]}""", true)
            assertEquals("hello", chat(s))
        }
    }
    @Test fun truncatedStreamIsReportedInsteadOfSilentSuccess() = runBlocking {
        server().use { s ->
            s.reply(event("""{"choices":[{"delta":{"content":"partial"}}]}"""))
            val e = runCatching { chat(s) }.exceptionOrNull()
            assertTrue(e is IllegalStateException)
            assertTrue(e!!.message!!.contains("прервалось"))
        }
    }
    @Test fun malformedStreamIsReported() = runBlocking {
        server().use { s -> s.reply(event("not-json")); assertTrue(runCatching { chat(s) }.exceptionOrNull() is IllegalStateException) }
    }
    @Test fun readsAnthropicEventsWithNativeHeaders() = runBlocking {
        server().use { s ->
            s.reply(event("""{"type":"content_block_delta","delta":{"type":"text_delta","text":"hello"}}""") + event("""{"type":"message_stop"}"""))
            assertEquals("hello", chat(s, ApiProtocols.ANTHROPIC))
            val r = s.takeRequest(); assertEquals("/v1/messages", r.path)
            assertEquals("test-key", r.getHeader("x-api-key")); assertNull(r.getHeader("Authorization"))
        }
    }
    @Test fun readsGeminiEventsAndRecognizesCompletion() = runBlocking {
        server().use { s ->
            s.reply(event("""{"candidates":[{"content":{"parts":[{"text":"hello"}]},"finishReason":"STOP"}]}"""))
            assertEquals("hello", chat(s, ApiProtocols.GEMINI))
            val r = s.takeRequest(); assertEquals("/v1/models/test-model:streamGenerateContent?alt=sse", r.path)
            assertEquals("test-key", r.getHeader("x-goog-api-key"))
        }
    }
    @Test fun readsResponsesEventsAndUsesTheResponsesEndpoint() = runBlocking {
        server().use { s ->
            s.reply(event("""{"type":"response.output_text.delta","delta":"hello"}""") + event("""{"type":"response.completed"}"""))
            assertEquals("hello", chat(s, ApiProtocols.OPENAI, reasoning = true))
            val r = s.takeRequest(); assertEquals("/v1/responses", r.path)
            assertEquals(4096, JSONObject(r.body.readUtf8()).getInt("max_output_tokens"))
        }
    }
    @Test fun tokenLimitIsNotReportedAsACompleteAnswer() = runBlocking {
        server().use { s ->
            s.reply(event("""{"choices":[{"delta":{"content":"partial"},"finish_reason":"length"}]}"""))
            val e = runCatching { chat(s) }.exceptionOrNull()
            assertTrue(e is IllegalStateException); assertTrue(e!!.message!!.contains("лимит токенов"))
        }
    }
    @Test fun customChatCompletionsActuallySendsSearchAndReasoning() = runBlocking {
        server().use { s ->
            s.reply(event("[DONE]")); chat(s, web = true, reasoning = true)
            val r = s.takeRequest(); assertEquals("/v1/chat/completions", r.path)
            val b = JSONObject(r.body.readUtf8())
            assertEquals("medium", b.getJSONObject("web_search_options").getString("search_context_size"))
            assertEquals("medium", b.getString("reasoning_effort"))
        }
    }
    @Test fun customOpenRouterSendsNativePluginAndReasoning() = runBlocking {
        server().use { s ->
            s.reply(event("[DONE]")); chat(s, ApiProtocols.OPENROUTER, web = true, reasoning = true)
            val b = JSONObject(s.takeRequest().body.readUtf8())
            assertEquals("web", b.getJSONArray("plugins").getJSONObject(0).getString("id"))
            assertEquals("medium", b.getJSONObject("reasoning").getString("effort")); assertFalse(b.has("web_search_options"))
        }
    }
    @Test fun customResponsesSendsToolsWithoutGuessingTheModelName() = runBlocking {
        server().use { s ->
            s.reply(event("""{"type":"response.completed"}""")); chat(s, ApiProtocols.RESPONSES, web = true, reasoning = true)
            val r = s.takeRequest(); assertEquals("/v1/responses", r.path)
            val b = JSONObject(r.body.readUtf8())
            assertEquals("web_search", b.getJSONArray("tools").getJSONObject(0).getString("type"))
            assertEquals("medium", b.getJSONObject("reasoning").getString("effort"))
        }
    }
    @Test fun disabledToolsAreNotSentToCompatibleServer() = runBlocking {
        server().use { s ->
            s.reply(event("[DONE]")); chat(s)
            val b = JSONObject(s.takeRequest().body.readUtf8()); assertFalse(b.has("web_search_options")); assertFalse(b.has("reasoning_effort"))
        }
    }
    @Test fun separatesReasoningFromFinalAnswerForCompatibleRouters() = runBlocking {
        server().use { s ->
            s.reply(event("""{"choices":[{"delta":{"reasoning_content":"Checking facts"}}]}""") + event("""{"choices":[{"delta":{"content":"Final answer"}}]}""") + event("[DONE]"))
            var thought = ""; assertEquals("Final answer", chat(s, onReasoning = { thought += it })); assertEquals("Checking facts", thought)
        }
    }
    @Test fun readsOpenRouterReasoningDetailsWithoutDisplayingEncryptedData() = runBlocking {
        server().use { s ->
            s.reply(event("""{"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.text","text":"Checking"},{"type":"reasoning.encrypted","data":"secret"}]}}]}""") + event("[DONE]"))
            var thought = ""; chat(s, ApiProtocols.OPENROUTER, onReasoning = { thought += it }); assertEquals("Checking", thought)
        }
    }
    @Test fun readsAnthropicThinkingDeltas() = runBlocking {
        server().use { s ->
            s.reply(event("""{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"Thinking"}}""") + event("""{"type":"message_stop"}"""))
            var thought = ""; chat(s, ApiProtocols.ANTHROPIC, onReasoning = { thought += it }); assertEquals("Thinking", thought)
        }
    }
    @Test fun readsResponsesReasoningSummary() = runBlocking {
        server().use { s ->
            s.reply(event("""{"type":"response.reasoning_summary_text.delta","delta":"Summary"}""") + event("""{"type":"response.completed"}"""))
            var thought = ""; chat(s, ApiProtocols.RESPONSES, onReasoning = { thought += it }); assertEquals("Summary", thought)
        }
    }
    @Test fun oneKeyGptUsesResponsesWithBearer() = runBlocking {
        server().use { s ->
            s.reply(event("""{"type":"response.completed"}""")); chat(s, routerProfile = "onekey", model = "gpt-5", web = true, reasoning = true)
            val r = s.takeRequest(); assertEquals("/v1/responses", r.path); assertEquals("Bearer test-key", r.getHeader("Authorization"))
            assertEquals("web_search", JSONObject(r.body.readUtf8()).getJSONArray("tools").getJSONObject(0).getString("type"))
        }
    }
    @Test fun eachCuratedChatProfileProducesARealHttpsRequest() = runBlocking {
        for (profile in RouterPresets.all.filter { it.id !in setOf("onekey", "openrouter") }) {
            server().use { s ->
                s.reply(event("[DONE]")); chat(s, routerProfile = profile.id, reasoning = true)
                val r = s.takeRequest(); assertEquals(profile.id, "/v1/chat/completions", r.path)
                val b = JSONObject(r.body.readUtf8())
                val field = when (profile.id) { "vercel", "together" -> "reasoning"; "novita", "siliconflow" -> "enable_thinking"; else -> "reasoning_effort" }
                assertTrue(profile.id, b.has(field)); assertFalse(profile.id, b.has("web_search_options"))
            }
        }
    }
    @Test fun oneKeyClaudeUsesMessagesAndBearerInsteadOfNativeKeyHeader() = runBlocking {
        server().use { s ->
            s.reply(event("""{"type":"message_stop"}""")); chat(s, routerProfile = "onekey", model = "claude-sonnet-4-6", reasoning = true)
            val r = s.takeRequest(); assertEquals("/v1/messages", r.path); assertEquals("Bearer test-key", r.getHeader("Authorization"))
            assertNull(r.getHeader("x-api-key")); assertTrue(JSONObject(r.body.readUtf8()).has("thinking"))
        }
    }
}
