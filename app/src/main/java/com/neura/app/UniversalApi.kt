package com.neura.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object Providers {
    val ids = listOf("openai", "anthropic", "gemini", "openrouter", "deepseek", "groq", "mistral", "together", "custom")
    fun name(id: String): String = mapOf("openai" to "OpenAI", "anthropic" to "Anthropic Claude", "gemini" to "Google Gemini", "openrouter" to "OpenRouter", "deepseek" to "DeepSeek", "groq" to "Groq", "mistral" to "Mistral", "together" to "Together AI", "custom" to "Свой API (OpenAI-совместимый)")[id] ?: id
    fun endpoint(id: String): String = mapOf(
        "openai" to "https://api.openai.com/v1",
        "anthropic" to "https://api.anthropic.com/v1",
        "gemini" to "https://generativelanguage.googleapis.com/v1beta",
        "openrouter" to "https://openrouter.ai/api/v1",
        "deepseek" to "https://api.deepseek.com/v1",
        "groq" to "https://api.groq.com/openai/v1",
        "mistral" to "https://api.mistral.ai/v1",
        "together" to "https://api.together.xyz/v1",
        "custom" to "https://example.com/v1"
    )[id] ?: "https://example.com/v1"
    fun defaultModel(id: String): String = mapOf(
        "openai" to "gpt-4.1-mini", "anthropic" to "claude-sonnet-4-5",
        "gemini" to "gemini-2.5-flash", "openrouter" to "openai/gpt-4.1-mini",
        "deepseek" to "deepseek-chat", "groq" to "llama-3.3-70b-versatile",
        "mistral" to "mistral-small-latest", "together" to "meta-llama/Llama-3.3-70B-Instruct-Turbo",
        "custom" to "my-model"
    )[id] ?: "my-model"
}

class UniversalApi {
    private val client = OkHttpClient.Builder().connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()

    suspend fun stream(provider: String, endpoint: String, key: String, model: String,
        systemPrompt: String, web: Boolean, maxTokens: Int, temperature: Double,
        history: List<Message>, onText: suspend (String) -> Unit) = withContext(Dispatchers.IO) {
        val base = endpoint.trim().trimEnd('/')
        require(base.startsWith("https://") && !base.contains('#') && !base.contains('?')) {
            "Используй HTTPS endpoint без параметров запроса"
        }
        // Prevent accidental key disclosure through username:password URLs or redirects.
        val parsed = okhttp3.base.toHttpUrlOrNull() ?: error("Неверный URL API")
        require(parsed.username.isBlank() && parsed.password.isBlank()) { "В URL нельзя указывать логин или пароль" }
        require(parsed.host != "example.com") { "Укажи настоящий адрес своего API" }
        val payload = JSONObject()
        val url: String
        val requestBuilder: Request.Builder
        when (provider) {
            "anthropic" -> {
                url = "$base/messages"
                payload.put("model", model).put("max_tokens", maxTokens).put("stream", true)
                if (systemPrompt.isNotBlank()) payload.put("system", systemPrompt)
                // Some Claude models reject temperature, so avoid forcing a default value.
                if (temperature != 1.0) payload.put("temperature", temperature)
                val msgs = JSONArray()
                history.filter { it.text.isNotBlank() }.forEach { m ->
                    msgs.put(JSONObject().put("role", m.role).put("content", m.text))
                }
                payload.put("messages", msgs)
                if (web) payload.put("tools", JSONArray().put(JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 5)))
                requestBuilder = Request.Builder().header("x-api-key", key).header("anthropic-version", "2023-06-01")
            }
            "gemini" -> {
                // The model id is encoded as a single path segment.
                val safeModel = java.net.URLEncoder.encode(model, "UTF-8").replace("+", "%20")
                url = "$base/models/$safeModel:streamGenerateContent?alt=sse"
                val contents = JSONArray()
                history.filter { it.text.isNotBlank() }.forEach { m ->
                    contents.put(JSONObject().put("role", if (m.role == "assistant") "model" else "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", m.text))))
                }
                payload.put("contents", contents)
                if (systemPrompt.isNotBlank()) payload.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                payload.put("generationConfig", JSONObject().put("temperature", temperature).put("maxOutputTokens", maxTokens))
                requestBuilder = Request.Builder().header("x-goog-api-key", key)
            }
            else -> {
                url = "$base/chat/completions"
                payload.put("model", model).put("stream", true)
                payload.put("temperature", temperature).put("max_tokens", maxTokens)
                val msgs = JSONArray()
                if (systemPrompt.isNotBlank()) msgs.put(JSONObject().put("role", "system").put("content", systemPrompt))
                history.filter { it.text.isNotBlank() }.forEach { m ->
                    msgs.put(JSONObject().put("role", m.role).put("content", m.text))
                }
                payload.put("messages", msgs)
                requestBuilder = Request.Builder()
                if (key.isNotBlank()) requestBuilder.header("Authorization", "Bearer $key")
                if (provider == "openrouter") requestBuilder.header("HTTP-Referer", "https://github.com/")
            }
        }
        val req = requestBuilder.url(url).header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val noRedirectClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val call = noRedirectClient.newCall(req)
        val cancellation = launch { try { awaitCancellation() } finally { call.cancel() } }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val raw = response.body?.string().orEmpty()
                    val obj = runCatching { JSONObject(raw) }.getOrNull()
                    val msg = obj?.optJSONObject("error")?.optString("message")
                    throw IllegalStateException("${Providers.name(provider)} HTTP ${response.code}: ${msg?.ifBlank { null } ?: raw.take(350)}")
                }
                val source = response.body?.source() ?: error("Пустой ответ API")
                var ended = false
                while (true) {
                    coroutineContext.ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val raw = line.removePrefix("data:").trim()
                    if (raw == "[DONE]") { ended = true; break }
                    if (raw.isBlank()) continue
                    val obj = runCatching { JSONObject(raw) }.getOrNull() ?: continue
                    when (provider) {
                        "anthropic" -> when (obj.optString("type")) {
                            "content_block_start" -> {
                                val block = obj.optJSONObject("content_block")
                                if (block?.optString("type") == "text") block.optString("text").takeIf { it.isNotEmpty() }?.let { onText(it) }
                            }
                            "content_block_delta" -> {
                                val delta = obj.optJSONObject("delta")
                                if (delta?.optString("type") == "text_delta") delta.optString("text").takeIf { it.isNotEmpty() }?.let { onText(it) }
                            }
                            "message_stop" -> ended = true
                            "error" -> throw IllegalStateException(obj.optJSONObject("error")?.optString("message") ?: "Ошибка Anthropic")
                        }
                        "gemini" -> {
                            val parts = obj.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                            if (parts != null) for (i in 0 until parts.length()) {
                                val part = parts.optJSONObject(i)
                                if (part?.optBoolean("thought") != true) part?.optString("text")?.takeIf { it.isNotEmpty() }?.let { onText(it) }
                            }
                            if (obj.has("error")) throw IllegalStateException(obj.optJSONObject("error")?.optString("message") ?: "Ошибка Gemini")
                        }
                        else -> {
                            val first = obj.optJSONArray("choices")?.optJSONObject(0)
                            val delta = first?.optJSONObject("delta")
                            val text = delta?.optString("content")
                            if (!text.isNullOrEmpty()) onText(text)
                            if (first != null && first.has("finish_reason") && !first.isNull("finish_reason")) ended = true
                            if (obj.has("error")) throw IllegalStateException(obj.optJSONObject("error")?.optString("message") ?: "Ошибка API")
                        }
                    }
                }
                if (!ended && provider != "gemini") {
                    // Some compatible providers end the stream by closing the socket.
                    // Content is preserved by the caller if a provider uses that convention.
                }
            }
        } finally { cancellation.cancel() }
    }
}
