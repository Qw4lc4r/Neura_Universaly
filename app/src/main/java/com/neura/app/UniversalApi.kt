package com.neura.app

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File
import java.util.UUID
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
    fun web(protocol: String, model: String): Boolean = when (protocol) {
        ApiProtocols.ANTHROPIC -> true
        ApiProtocols.GEMINI -> true
        ApiProtocols.OPENAI, ApiProtocols.OPENAI_COMPAT, ApiProtocols.OPENROUTER -> true
        else -> false
    }

    fun reasoning(protocol: String, model: String): Boolean = when (protocol) {
        ApiProtocols.ANTHROPIC -> model.startsWith("claude-")
        ApiProtocols.GEMINI -> true
        ApiProtocols.OPENAI -> model.startsWith("o") || model.startsWith("gpt-5") || model.startsWith("gpt-6")
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
        protocol: String,
        endpoint: String,
        key: String,
        model: String,
        systemPrompt: String,
        web: Boolean,
        webMode: String,
        reasoning: Boolean,
        maxTokens: Int,
        temperature: Double,
        history: List<Message>,
        imageMode: Boolean,
        imageApi: Boolean,
        onText: suspend (String) -> Unit,
        onImage: suspend (Attachment) -> Unit
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

        if (imageMode && ApiProtocols.imageRoute(protocol, imageApi) != "chat_images") {
            require(ApiProtocols.imageRoute(protocol, imageApi) != "unsupported") {
                "У выбранного протокола нет Image API. Выбери другой протокол подключения."
            }
            generateImages(context, protocol, base, key, model, history, onText, onImage)
            return@withContext
        }
        val preparedHistory = history.map { it to prepareAttachments(context, it.attachments) }
        val payload = JSONObject()
        val url: String
        val requestBuilder: Request.Builder
        val searchMode = WebSearchModes.resolve(protocol, webMode)
        val useResponses = (protocol == ApiProtocols.OPENAI || protocol == ApiProtocols.OPENAI_COMPAT || protocol == ApiProtocols.OPENROUTER) &&
            ((web && searchMode == WebSearchModes.RESPONSES) || (protocol == ApiProtocols.OPENAI && reasoning))
        // Search-specific Chat Completions endpoints may return plain JSON instead of SSE.
        val unstreamedWebChat = web && !useResponses && searchMode == WebSearchModes.CHAT_OPTIONS

        when (protocol) {
            ApiProtocols.ANTHROPIC -> {
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
                                    .put("data", Base64.encodeToString(item.bytes, Base64.NO_WRAP))
                            )
                        )
                    }
                    msgs.put(JSONObject().put("role", m.role).put("content", content))
                }
                payload.put("messages", msgs)
                if (web) payload.put("tools", JSONArray().put(JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 5)))
                requestBuilder = Request.Builder().header("x-api-key", key).header("anthropic-version", "2023-06-01")
            }

            ApiProtocols.GEMINI -> {
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
                                JSONObject().put("mimeType", item.attachment.mimeType).put("data", Base64.encodeToString(item.bytes, Base64.NO_WRAP))
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

            ApiProtocols.OPENAI -> {
                url = if (useResponses) "$base/responses" else "$base/chat/completions"
                payload.put("model", model).put("stream", if (useResponses) true else !imageMode && !unstreamedWebChat)
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
                            val dataUrl = "data:${item.attachment.mimeType};base64,${Base64.encodeToString(item.bytes, Base64.NO_WRAP)}"
                            content.put(JSONObject().put("type", "input_image").put("image_url", dataUrl))
                        }
                        inputs.put(JSONObject().put("role", m.role).put("content", content))
                    }
                    payload.put("input", inputs)
                    if (web) payload.put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
                    if (reasoning) payload.put("reasoning", JSONObject().put("effort", "medium"))
                } else {
                    payload.put("temperature", temperature).put("max_tokens", maxTokens)
                    if (imageMode) payload.put("modalities", JSONArray().put("text").put("image"))
                    val msgs = JSONArray()
                    if (systemPrompt.isNotBlank()) msgs.put(JSONObject().put("role", "system").put("content", systemPrompt))
                    preparedHistory.filter { it.first.text.isNotBlank() || it.second.isNotEmpty() }.forEach { (m, prepared) ->
                        msgs.put(JSONObject().put("role", m.role).put("content", openAiCompatibleContent(m.text, prepared)))
                    }
                    payload.put("messages", msgs)
                    if (web && searchMode == WebSearchModes.CHAT_OPTIONS) {
                        payload.put("web_search_options", JSONObject().put("search_context_size", "medium"))
                    }
                }
                requestBuilder = Request.Builder().header("Authorization", "Bearer $key")
            }

            else -> {
                url = if (useResponses) "$base/responses" else "$base/chat/completions"
                payload.put("model", model).put("stream", if (useResponses) true else !imageMode && !unstreamedWebChat)
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
                            val dataUrl = "data:${item.attachment.mimeType};base64,${Base64.encodeToString(item.bytes, Base64.NO_WRAP)}"
                            content.put(JSONObject().put("type", "input_image").put("image_url", dataUrl))
                        }
                        inputs.put(JSONObject().put("role", m.role).put("content", content))
                    }
                    payload.put("input", inputs)
                    if (web) payload.put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
                } else {
                    // Do not send optional sampling settings to specialized web-search endpoints.
                    if (!unstreamedWebChat) payload.put("temperature", temperature).put("max_tokens", maxTokens)
                    if (imageMode) payload.put("modalities", JSONArray().put("text").put("image"))
                    val msgs = JSONArray()
                    if (systemPrompt.isNotBlank()) msgs.put(JSONObject().put("role", "system").put("content", systemPrompt))
                    preparedHistory.filter { it.first.text.isNotBlank() || it.second.isNotEmpty() }.forEach { (m, prepared) ->
                        msgs.put(JSONObject().put("role", m.role).put("content", openAiCompatibleContent(m.text, prepared)))
                    }
                    payload.put("messages", msgs)
                    if (web) when (searchMode) {
                        WebSearchModes.CHAT_OPTIONS -> payload.put("web_search_options", JSONObject().put("search_context_size", "medium"))
                        WebSearchModes.OPENROUTER_PLUGIN -> payload.put("plugins", JSONArray().put(JSONObject().put("id", "web").put("max_results", 5)))
                    }
                }
                requestBuilder = Request.Builder()
                if (key.isNotBlank()) requestBuilder.header("Authorization", "Bearer $key")
                if (protocol == ApiProtocols.OPENROUTER) requestBuilder.header("HTTP-Referer", "https://github.com/")
            }
        }

        val unstreamedChatImage = imageMode && !imageApi && !useResponses && protocol != ApiProtocols.GEMINI
        val unstreamedCompatible = unstreamedChatImage || unstreamedWebChat
        val req = requestBuilder.url(url)
            .header("Accept", if (unstreamedCompatible) "application/json" else "text/event-stream")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val noRedirectClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val call = noRedirectClient.newCall(req)
        val cancellation = kotlinx.coroutines.CoroutineScope(coroutineContext).launch { try { awaitCancellation() } finally { call.cancel() } }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val raw = response.body?.string().orEmpty()
                    val apiError = ApiHttpException.from(response.code, raw, response.header("Retry-After"))
                    if (web && response.code in listOf(400, 404, 422)) {
                        throw IllegalStateException(
                            "Роутер отклонил веб-поиск (${WebSearchModes.label(searchMode)}). " +
                                "Проверь поддержку поиска или выбери другой способ в Настройки → Модель. " + apiError.message
                        )
                    }
                    throw apiError
                }
                if (unstreamedCompatible) {
                    val raw = response.body?.string().orEmpty()
                    val obj = JSONObject(raw)
                    val choice = obj.optJSONArray("choices")?.optJSONObject(0)
                        ?: throw IllegalStateException("Модель не вернула изображение или текст")
                    consumeCompatibleChoice(context, choice, onText, onImage)
                    appendSearchSources(obj, choice, onText)
                    if (unstreamedWebChat && protocol == ApiProtocols.OPENAI_COMPAT && !hasSearchEvidence(obj, choice)) {
                        onText("\n\n> ⚠️ API не прислал подтверждение веб-поиска или ссылки на источники. " +
                            "Роутер мог проигнорировать параметр `web_search_options`. " +
                            "Если поиск не работает, выбери другой способ в настройках.")
                    }
                    return@use
                }
                val source = response.body?.source() ?: error("Пустой ответ API")
                var ended = false
                val streamedSources = linkedMapOf<String, String>()
                while (true) {
                    coroutineContext.ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val raw = line.removePrefix("data:").trim()
                    if (raw == "[DONE]") { ended = true; break }
                    if (raw.isBlank()) continue
                    val obj = runCatching { JSONObject(raw) }.getOrNull() ?: continue
                    when (protocol) {
                        ApiProtocols.ANTHROPIC -> when (obj.optString("type")) {
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
                        ApiProtocols.GEMINI -> {
                            val parts = obj.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                            if (parts != null) {
                                for (i in 0 until parts.length()) {
                                    val part = parts.optJSONObject(i)
                                    if (part?.optBoolean("thought") != true) part?.optString("text")?.takeIf { it.isNotEmpty() }?.let { onText(it) }
                                    part?.optJSONObject("inlineData")?.let { inline ->
                                        saveEncodedImage(context, inline.optString("data"), inline.optString("mimeType", "image/png"))?.let { onImage(it) }
                                    }
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
                                if (first != null) {
                                    consumeCompatibleChoice(context, first, onText, onImage)
                                    collectSearchSources(obj, first, streamedSources)
                                }
                                if (first != null && first.has("finish_reason") && !first.isNull("finish_reason")) ended = true
                                if (obj.has("error")) throw IllegalStateException(obj.optJSONObject("error")?.optString("message") ?: "Ошибка API")
                            }
                        }
                    }
                }
                if (web && streamedSources.isNotEmpty()) {
                    onText("\n\nИсточники: " + streamedSources.entries.take(8).joinToString(" · ") { (url, title) -> "[$title]($url)" })
                }
                if (!ended && protocol != ApiProtocols.GEMINI) {
                    // Some compatible providers close the socket to finish.
                }
            }
        } finally {
            cancellation.cancel()
        }
    }

    private fun hasSearchEvidence(response: JSONObject, choice: JSONObject): Boolean {
        if ((response.optJSONArray("citations")?.length() ?: 0) > 0) return true
        if ((choice.optJSONObject("message")?.optJSONArray("annotations")?.length() ?: 0) > 0) return true
        if ((choice.optJSONObject("message")?.optJSONArray("citations")?.length() ?: 0) > 0) return true
        return false
    }

    private fun collectSearchSources(response: JSONObject, choice: JSONObject, result: MutableMap<String, String>) {
        fun collect(array: JSONArray?) {
            if (array == null) return
            for (i in 0 until array.length()) {
                val entry = array.opt(i)
                val obj = entry as? JSONObject
                val citation = obj?.optJSONObject("url_citation") ?: obj
                val url = when (entry) {
                    is String -> entry
                    else -> citation?.optString("url").orEmpty()
                }
                if (!url.startsWith("https://") || url.length > 2048 ||
                    url.any { it.isWhitespace() || it in "[]()" } || result.size >= 8) continue
                val title = citation?.optString("title")?.take(70)?.takeIf { it.isNotBlank() }
                    ?: runCatching { java.net.URI(url).host }.getOrNull() ?: "Источник"
                result.putIfAbsent(url, title.replace(']', ' ').replace('[', ' '))
            }
        }
        collect(response.optJSONArray("citations"))
        collect(choice.optJSONObject("message")?.optJSONArray("citations"))
        collect(choice.optJSONObject("message")?.optJSONArray("annotations"))
        collect(choice.optJSONObject("delta")?.optJSONArray("annotations"))
    }

    private suspend fun appendSearchSources(
        response: JSONObject, choice: JSONObject, onText: suspend (String) -> Unit
    ) {
        val sources = linkedMapOf<String, String>()
        collectSearchSources(response, choice, sources)
        if (sources.isNotEmpty()) {
            onText("\n\nИсточники: " + sources.entries.joinToString(" · ") { (url, title) -> "[$title]($url)" })
        }
    }

    private suspend fun consumeCompatibleChoice(
        context: Context, choice: JSONObject,
        onText: suspend (String) -> Unit,
        onImage: suspend (Attachment) -> Unit
    ) {
        val message = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return
        val parts = message.optJSONArray("content")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                when (part.optString("type")) {
                    "text", "output_text" -> part.optString("text").takeIf { it.isNotBlank() }?.let { onText(it) }
                    "image", "image_url" -> consumeImageValue(
                        context,
                        part.optJSONObject("image_url")?.optString("url")
                            ?: part.optString("image_url").ifBlank { part.optString("url") },
                        onText, onImage
                    )
                }
            }
        } else {
            message.optString("content").takeIf { it.isNotBlank() }?.let { onText(it) }
        }
        val images = message.optJSONArray("images") ?: JSONArray()
        for (i in 0 until images.length()) {
            val item = images.optJSONObject(i) ?: continue
            val imageUrl = item.optJSONObject("image_url")?.optString("url")
                ?: item.optString("image_url").ifBlank { item.optString("url") }
            consumeImageValue(context, imageUrl, onText, onImage)
        }
    }

    private suspend fun consumeImageValue(
        context: Context, value: String,
        onText: suspend (String) -> Unit,
        onImage: suspend (Attachment) -> Unit
    ) {
        when {
            value.startsWith("data:image/") && value.contains("base64,") -> {
                val mime = value.substringAfter("data:").substringBefore(';')
                saveEncodedImage(context, value.substringAfter("base64,"), mime)?.let { onImage(it) }
            }
            value.startsWith("https://") -> onText("\n![Изображение]($value)")
        }
    }

    private suspend fun generateImages(
        context: Context, protocol: String, base: String, key: String, model: String,
        history: List<Message>, onText: suspend (String) -> Unit,
        onImage: suspend (Attachment) -> Unit
    ) {
        val last = history.lastOrNull { it.role == "user" }
        val prompt = last?.text?.trim().orEmpty()
        require(prompt.isNotBlank()) { "Для генерации изображения нужен текстовый запрос" }
        val payload = JSONObject().put("model", model).put("prompt", prompt).put("n", 1)
        val url: String
        val builder = Request.Builder().header("Accept", "application/json")
        when (protocol) {
            ApiProtocols.OPENROUTER -> {
                url = "$base/images"
                if (key.isNotBlank()) builder.header("Authorization", "Bearer $key")
                // Reference images need a compatible image endpoint; unsupported models may reject them.
                val source = last?.attachments?.firstOrNull { isImageMime(it.mimeType) }
                if (source != null) {
                    val bytes = readBytes(context, Uri.parse(source.uri), 8 * 1024 * 1024)
                    if (bytes != null) {
                        val data = "data:${source.mimeType};base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
                        payload.put("input_references", JSONArray().put(JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject().put("url", data))))
                    }
                }
            }
            ApiProtocols.OPENAI_COMPAT -> {
                url = "$base/images/generations"
                if (key.isNotBlank()) builder.header("Authorization", "Bearer $key")
            }
            ApiProtocols.OPENAI -> {
                url = "$base/images/generations"
                builder.header("Authorization", "Bearer $key")
                // GPT Image endpoints return base64 by default; legacy response_format is not required.
            }
            ApiProtocols.GEMINI -> {
                val safeModel = java.net.URLEncoder.encode(model, "UTF-8").replace("+", "%20")
                url = "$base/models/$safeModel:generateContent"
                builder.header("x-goog-api-key", key)
                val parts = JSONArray().put(JSONObject().put("text", prompt))
                last?.attachments?.filter { isImageMime(it.mimeType) }?.forEach { a ->
                    val bytes = readBytes(context, Uri.parse(a.uri), 8 * 1024 * 1024)
                    if (bytes != null) parts.put(JSONObject().put("inlineData", JSONObject()
                        .put("mimeType", a.mimeType).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))))
                }
                val geminiPayload = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                    .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
                return postImageRequest(context, builder, url, geminiPayload, true, onText, onImage)
            }
            else -> error("Для выбранного протокола генерация изображений не поддерживается")
        }
        postImageRequest(context, builder, url, payload, false, onText, onImage)
    }

    private suspend fun postImageRequest(
        context: Context, builder: Request.Builder, url: String, payload: JSONObject,
        gemini: Boolean, onText: suspend (String) -> Unit, onImage: suspend (Attachment) -> Unit
    ) {
        val request = builder.url(url)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val call = client.newBuilder().followRedirects(false).followSslRedirects(false).build().newCall(request)
        val cancellation = kotlinx.coroutines.CoroutineScope(coroutineContext).launch { try { awaitCancellation() } finally { call.cancel() } }
        try {
            call.execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ApiHttpException.from(response.code, raw, response.header("Retry-After"))
                val json = JSONObject(raw)
                var count = 0
                if (gemini) {
                    val parts = json.optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                    for (i in 0 until parts.length()) {
                        coroutineContext.ensureActive()
                        val part = parts.optJSONObject(i) ?: continue
                        part.optString("text").takeIf { it.isNotBlank() }?.let { onText(it) }
                        val inline = part.optJSONObject("inlineData") ?: continue
                        saveEncodedImage(context, inline.optString("data"), inline.optString("mimeType", "image/png"))?.let {
                            onImage(it); count++
                        }
                    }
                } else {
                    val data = json.optJSONArray("data") ?: JSONArray()
                    for (i in 0 until data.length()) {
                        coroutineContext.ensureActive()
                        val item = data.optJSONObject(i) ?: continue
                        saveEncodedImage(context, item.optString("b64_json"), item.optString("media_type", "image/png"))?.let {
                            onImage(it); count++
                        }
                        // Some compatible image endpoints only return a hosted URL.
                        val urlValue = item.optString("url")
                        if (urlValue.startsWith("https://")) { onText("\n![Изображение]($urlValue)"); count++ }
                    }
                }
                if (count == 0) throw IllegalStateException("API ответил без изображения. Выбери модель с поддержкой генерации изображений.")
            }
        } finally { cancellation.cancel() }
    }

    private fun saveEncodedImage(context: Context, encoded: String, mimeHint: String): Attachment? {
        if (encoded.isBlank()) return null
        val normalized = encoded.substringAfter("base64,", encoded)
        // Bound decoded memory consumption and avoid writing malformed data.
        if (normalized.length > 45_000_000) throw IllegalArgumentException("Изображение слишком большое")
        val bytes = Base64.decode(normalized, Base64.DEFAULT)
        require(bytes.size in 1..30_000_000) { "Получено слишком большое изображение" }
        val mime = when {
            bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
            bytes.size > 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) -> "image/webp"
            mimeHint == "image/svg+xml" -> "image/svg+xml"
            else -> "image/png"
        }
        val ext = when (mime) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; "image/svg+xml" -> "svg"; else -> "png" }
        val name = "neura-${java.util.UUID.randomUUID()}.$ext"
        val dir = java.io.File(context.filesDir, "generated_images").apply { mkdirs() }
        val file = java.io.File(dir, name).apply { writeBytes(bytes) }
        return Attachment(name = name, uri = Uri.fromFile(file).toString(), mimeType = mime, size = bytes.size.toLong())
    }

    private fun openAiCompatibleContent(text: String, prepared: List<PreparedAttachment>): Any {
        val items = JSONArray()
        messageTextWithFiles(text, prepared).takeIf { it.isNotBlank() }?.let {
            items.put(JSONObject().put("type", "text").put("text", it))
        }
        prepared.filter { isImageMime(it.attachment.mimeType) && it.bytes != null }.forEach { item ->
            val dataUrl = "data:${item.attachment.mimeType};base64,${Base64.encodeToString(item.bytes, Base64.NO_WRAP)}"
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
                if (total > limit) throw IllegalArgumentException("Изображение слишком большое (максимум ${limit / (1024 * 1024)} MB)")
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
