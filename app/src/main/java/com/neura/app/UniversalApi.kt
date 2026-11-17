package com.neura.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

object Providers {
    val ids = listOf("openai", "anthropic", "gemini", "openrouter", "deepseek", "groq", "mistral", "together", "custom")
    fun name(id: String): String = mapOf(
        "openai" to "OpenAI",
        "anthropic" to "Anthropic Claude",
        "gemini" to "Google Gemini",
        "openrouter" to "OpenRouter",
        "deepseek" to "DeepSeek",
        "groq" to "Groq",
        "mistral" to "Mistral",
        "together" to "Together AI",
        "custom" to "Свой API (OpenAI-совместимый)"
    )[id] ?: id

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

    fun suggestions(id: String): List<String> = when (id) {
        "openai" -> listOf("gpt-5.4", "gpt-4.1-mini", "gpt-5-mini")
        "anthropic" -> listOf("claude-sonnet-4-5", "claude-sonnet-4-6", "claude-opus-4-6")
        "gemini" -> listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-3-flash-preview")
        else -> listOf(defaultModel(id))
    }

    fun defaultModel(id: String): String = mapOf(
        "openai" to "gpt-4.1-mini",
        "anthropic" to "claude-sonnet-4-5",
        "gemini" to "gemini-2.5-flash",
        "openrouter" to "openai/gpt-4.1-mini",
        "deepseek" to "deepseek-chat",
        "groq" to "llama-3.3-70b-versatile",
        "mistral" to "mistral-small-latest",
        "together" to "meta-llama/Llama-3.3-70B-Instruct-Turbo",
        "custom" to "my-model"
    )[id] ?: "my-model"
}

object ApiCapabilities {
    fun web(provider: String, model: String): Boolean = when (provider) {
        "anthropic" -> true
        "gemini" -> model.startsWith("gemini-")
        "openai" -> true
        else -> false
    }

    fun reasoning(provider: String, model: String): Boolean = when (provider) {
        "anthropic" -> model.startsWith("claude-")
        "gemini" -> model.startsWith("gemini-2.5") || model.startsWith("gemini-3")
        "openai" -> model.startsWith("o") || model.startsWith("gpt-5") || model.startsWith("gpt-6")
        else -> false
    }
}

private data class PreparedAttachment(
    val attachment: Attachment,
    val bytes: ByteArray? = null,
    val textContent: String? = null
)

class UniversalApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    suspend fun stream(
        context: Context,
        provider: String,
        endpoint: String,
        key: String,
        model: String,
        systemPrompt: String,
        web: Boolean,
        reasoning: Boolean,
        maxTokens: Int,
        temperature: Double,
        history: List<Message>,
        onText: suspend (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val base = endpoint.trim().trimEnd('/')
        require(base.startsWith("https://") && !base.contains('#') && !base.contains('?')) {
            "Используй HTTPS endpoint без параметров запроса"
        }
        val parsed = java.net.URI(base)
        require(parsed.scheme.equals("https", ignoreCase = true) && !parsed.host.isNullOrBlank()) {
            "Неверный HTTPS URL API"
        }
        require(parsed.rawUserInfo == null) { "В URL нельзя указывать логин или пароль" }
        require(parsed.host != "example.com") { "Укажи настоящий адрес своего API" }

        val preparedHistory = history.map { it to prepareAttachments(context, it.attachments) }
        val payload = JSONObject()
        val url: String
        val requestBuilder: Request.Builder
        val useResponses = provider == "openai" && (web || reasoning)

        when (provider) {
            "anthropic" -> {
                url = "$base/messages"
                payload.put("model", model).put("max_tokens", maxTokens).put("stream", true)
                if (systemPrompt.isNotBlank()) payload.put("system", systemPrompt)
                if (reasoning) {
                    if (model.contains("4-5") || model.contains("4-0") || model.contains("3-")) {
                        payload.put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", 1024))
                    } else {
                        payload.put("thinking", JSONObject().put("type", "adaptive"))
                    }
                } else if (temperature != 1.0) payload.put("temperature", temperature)

                val msgs = JSONArray()
                preparedHistory.filter { it.first.text.isNotBlank() || it.second.isNotEmpty() }.forEach { (m, prepared) ->
                    val content = JSONArray()
                    messageTextWithFiles(m.text, prepared).takeIf { it.isNotBlank() }?.let {
                        content.put(JSONObject().put("type", "text").put("text", it))
                    }
                    prepared.filter { isImageMime(it.attachment.mimeType) && it.bytes != null }.forEach { item ->
                        content.put(
                            JSONObject().put("type", "image").put(
                                "source", JSONObject()
                                    .put("type", "base64")
                                    .put("media_type", item.attachment.mimeType)
                                    .put("data", Base64.getEncoder().encodeToString(item.bytes))
                            )
                        )
                    }
                    msgs.put(JSONObject().put("role", m.role).put("content", content))
                }
                payload.put("messages", msgs)
                if (web) payload.put("tools", JSONArray().put(JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 5)))
                requestBuilder = Request.Builder().header("x-api-key", key).header("anthropic-version", "2023-06-01")
            }

            "gemini" -> {
                val safeModel = URLEncoder.encode(model, "UTF-8").replace("+", "%20")
                url = "$base/models/$safeModel:streamGenerateContent?alt=sse"
                val contents = JSONArray()
                preparedHistory.filter { it.first.text.isNotBlank() || it.second.isNotEmpty() }.forEach { (m, prepared) ->
                    val parts = JSONArray()
                    messageTextWithFiles(m.text, prepared).takeIf { it.isNotBlank() }?.let {
                        parts.put(JSONObject().put("text", it))
                    }
                    prepared.filter { isImageMime(it.attachment.mimeType) && it.bytes != null }.forEach { item ->
                        parts.put(
                            JSONObject().put(
                                "inlineData",
                                JSONObject().put("mimeType", item.attachment.mimeType).put("data", Base64.getEncoder().encodeToString(item.bytes))
                            )
                        )
                    }
                    contents.put(JSONObject().put("role", if (m.role == "assistant") "model" else "user").put("parts", parts))
                }
                payload.put("contents", contents)
                if (systemPrompt.isNotBlank()) {
                    payload.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                }
                val config = JSONObject().put("temperature", temperature).put("maxOutputTokens", maxTokens)
                if (reasoning) {
                    val thinking = if (model.startsWith("gemini-3")) JSONObject().put("thinkingLevel", "HIGH") else JSONObject().put("thinkingBudget", 1024)
                    config.put("thinkingConfig", thinking)
                }
                payload.put("generationConfig", config)
                if (web) payload.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                requestBuilder = Request.Builder().header("x-goog-api-key", key)
            }

            "openai" -> {
                url = if (useResponses) "$base/responses" else "$base/chat/completions"
                payload.put("model", model).put("stream", true)
                if (useResponses) {
                    payload.put("max_output_tokens", maxTokens)
                    if (systemPrompt.isNotBlank()) payload.put("instructions", systemPrompt)
                    val inputs = JSONArray()
                    preparedHistory.filter { it.first.text.isNotBlank() || it.second.isNotEmpty() }.forEach { (m, prepared) ->
                        val content = JSONArray()
                        messageTextWithFiles(m.text, prepared).takeIf { it.isNotBlank() }?.let {
                            content.put(JSONObject().put("type", "input_text").put("text", it))
                        }
                        prepared.filter { isImageMime(it.attachment.mimeType) && it.bytes != null }.forEach { item ->
                            val dataUrl = "data:${item.attachment.mimeType};base64,${Base64.getEncoder().encodeToString(item.bytes)}"
                            content.put(JSONObject().put("type", "input_image").put("image_url", dataUrl))
                        }
                        inputs.put(JSONObject().put("role", m.role).put("content", content))
                    }
                    payload.put("input", inputs)
                    if (web) payload.put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
                    if (reasoning) payload.put("reasoning", JSONObject().put("effort", "medium"))
                } else {
                    payload.put("temperature", temperature).put("max_tokens", maxTokens)
                    val msgs = JSONArray()
                    if (systemPrompt.isNotBlank()) msgs.put(JSONObject().put("role", "system").put("content", systemPrompt))
                    preparedHistory.filter { it.first.text.isNotBlank() || it.second.isNotEmpty() }.forEach { (m, prepared) ->
                        msgs.put(JSONObject().put("role", m.role).put("content", openAiCompatibleContent(m.text, prepared)))
                    }
                    payload.put("messages", msgs)
                }
                requestBuilder = Request.Builder().header("Authorization", "Bearer $key")
            }

            else -> {
                url = "$base/chat/completions"
                payload.put("model", model).put("stream", true)
                payload.put("temperature", temperature).put("max_tokens", maxTokens)
                val msgs = JSONArray()
                if (systemPrompt.isNotBlank()) msgs.put(JSONObject().put("role", "system").put("content", systemPrompt))
                preparedHistory.filter { it.first.text.isNotBlank() || it.second.isNotEmpty() }.forEach { (m, prepared) ->
                    msgs.put(JSONObject().put("role", m.role).put("content", openAiCompatibleContent(m.text, prepared)))
                }
                payload.put("messages", msgs)
                requestBuilder = Request.Builder()
                if (key.isNotBlank()) requestBuilder.header("Authorization", "Bearer $key")
                if (provider == "openrouter") requestBuilder.header("HTTP-Referer", "https://github.com/")
            }
        }

        val req = requestBuilder.url(url)
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

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
                            if (parts != null) {
                                for (i in 0 until parts.length()) {
                                    val part = parts.optJSONObject(i)
                                    if (part?.optBoolean("thought") != true) part?.optString("text")?.takeIf { it.isNotEmpty() }?.let { onText(it) }
                                }
                            }
                            if (obj.has("error")) throw IllegalStateException(obj.optJSONObject("error")?.optString("message") ?: "Ошибка Gemini")
                        }
                        else -> {
                            if (useResponses) {
                                when (obj.optString("type")) {
                                    "response.output_text.delta" -> obj.optString("delta").takeIf { it.isNotEmpty() }?.let { onText(it) }
                                    "response.completed" -> ended = true
                                    "response.failed", "error" -> throw IllegalStateException(
                                        obj.optJSONObject("response")?.optJSONObject("error")?.optString("message")
                                            ?: obj.optJSONObject("error")?.optString("message")
                                            ?: "Ошибка OpenAI Responses"
                                    )
                                }
                            } else {
                                val first = obj.optJSONArray("choices")?.optJSONObject(0)
                                val delta = first?.optJSONObject("delta")
                                val text = delta?.optString("content")
                                if (!text.isNullOrEmpty()) onText(text)
                                if (first != null && first.has("finish_reason") && !first.isNull("finish_reason")) ended = true
                                if (obj.has("error")) throw IllegalStateException(obj.optJSONObject("error")?.optString("message") ?: "Ошибка API")
                            }
                        }
                    }
                }
                if (!ended && provider != "gemini") {
                    // Some compatible providers close the socket to finish.
                }
            }
        } finally {
            cancellation.cancel()
        }
    }

    private fun openAiCompatibleContent(text: String, prepared: List<PreparedAttachment>): Any {
        val items = JSONArray()
        messageTextWithFiles(text, prepared).takeIf { it.isNotBlank() }?.let {
            items.put(JSONObject().put("type", "text").put("text", it))
        }
        prepared.filter { isImageMime(it.attachment.mimeType) && it.bytes != null }.forEach { item ->
            val dataUrl = "data:${item.attachment.mimeType};base64,${Base64.getEncoder().encodeToString(item.bytes)}"
            items.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUrl)))
        }
        return if (items.length() == 1 && items.optJSONObject(0)?.optString("type") == "text") {
            items.getJSONObject(0).optString("text")
        } else items
    }

    private fun messageTextWithFiles(text: String, prepared: List<PreparedAttachment>): String {
        val parts = mutableListOf<String>()
        if (text.isNotBlank()) parts += text
        prepared.filter { !isImageMime(it.attachment.mimeType) }.forEach { item ->
            val content = item.textContent?.take(12000)
            parts += buildString {
                append("\n\n[Вложение: ${item.attachment.name}; type=${item.attachment.mimeType}")
                if (item.attachment.size > 0) append("; size=${item.attachment.size}")
                append("]")
                if (!content.isNullOrBlank()) append("\n").append(content)
            }
        }
        return parts.joinToString("\n")
    }

    private fun prepareAttachments(context: Context, attachments: List<Attachment>): List<PreparedAttachment> {
        return attachments.mapNotNull { attachment ->
            try {
                val uri = Uri.parse(attachment.uri)
                val bytes = if (isImageMime(attachment.mimeType)) readBytes(context, uri, 8 * 1024 * 1024) else null
                val text = if (!isImageMime(attachment.mimeType)) readTextIfPossible(context, uri, attachment.mimeType) else null
                PreparedAttachment(attachment, bytes = bytes, textContent = text)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun readBytes(context: Context, uri: Uri, limit: Int): ByteArray? {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8192)
            val out = ByteArrayOutputStream()
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > limit) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
        return null
    }

    private fun readTextIfPossible(context: Context, uri: Uri, mimeType: String): String? {
        val textLike = mimeType.startsWith("text/") || mimeType in setOf(
            "application/json",
            "application/xml",
            "text/markdown",
            "application/javascript",
            "text/csv"
        )
        if (!textLike) return null
        return context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            reader.readText().take(16000)
        }
    }
}
