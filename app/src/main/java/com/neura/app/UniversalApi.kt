package com.neura.app

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

object Providers {
    fun configurable(id: String) = isCustom(id) || id in setOf("onekey", "litellm", "vercel")
    fun isCustom(id: String) = id == "custom" || id.startsWith("custom_")
    val ids = listOf("openai", "anthropic", "gemini", "openrouter", "deepseek", "groq", "mistral", "together", "custom")
    fun name(id: String): String = RouterPresets.get(id)?.name ?: mapOf(
        "openai" to "OpenAI",
        "anthropic" to "Anthropic Claude",
        "gemini" to "Google Gemini",
        "openrouter" to "OpenRouter",
        "deepseek" to "DeepSeek",
        "groq" to "Groq",
        "mistral" to "Mistral",
        "together" to "Together AI",
        "custom" to "Свой API / роутер"
    )[id] ?: id

    fun endpoint(id: String): String = RouterPresets.get(id)?.endpoint ?: mapOf(
        "openai" to "https://api.openai.com/v1",
        "anthropic" to "https://api.anthropic.com/v1",
        "gemini" to "https://generativelanguage.googleapis.com/v1beta",
        "openrouter" to "https://openrouter.ai/api/v1",
        "deepseek" to "https://api.deepseek.com/v1",
        "groq" to "https://api.groq.com/openai/v1",
        "mistral" to "https://api.mistral.ai/v1",
        "together" to "https://api.together.xyz/v1",
        "custom" to ""
    )[id] ?: ""

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
        "deepseek" to "deepseek-flash",
        "groq" to "llama-3.3-70b-versatile",
        "mistral" to "mistral-small-latest",
        "together" to "meta-llama/Llama-3.3-70B-Instruct-Turbo",
        "custom" to ""
    )[id] ?: ""
}

object ApiCapabilities {
    // The server, not the model ID or company name, decides actual availability.
    fun web(protocol: String, model: String): Boolean = protocol in ApiProtocols.customizable
    fun reasoning(protocol: String, model: String): Boolean = protocol in ApiProtocols.customizable

    fun openAiReasoningModel(model: String): Boolean =
        model.matches(Regex("""(?:o[1-9]|gpt-[56])(?:[.\-].*)?"""))
}

private data class PreparedAttachment(
    val attachment: Attachment,
    val bytes: ByteArray? = null,
    val textContent: String? = null
)

class UniversalApi(private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()) {

    suspend fun stream(
        context: Context,
        protocol: String,
        endpoint: String,
        key: String,
        model: String,
        systemPrompt: String,
        web: Boolean,
        reasoning: Boolean,
        maxTokens: Int,
        temperature: Double,
        history: List<Message>,
        imageMode: Boolean,
        imageApi: Boolean,
        onText: suspend (String) -> Unit,
        onImage: suspend (Attachment) -> Unit,
        onReasoning: suspend (String) -> Unit = {},
        routerProfile: String = "auto",
        routerReasoning: RouterReasoning? = null,
        reasoningFormat: ReasoningFormat = ReasoningFormat.AUTO
    ) = withContext(Dispatchers.IO) {
        val base = ApiConnection.baseUrl(endpoint)
        val activeProtocol = RouterPresets.protocol(protocol, routerProfile, base, model)
        val preset = RouterPresets.resolve(routerProfile, base)

        if (imageMode && ApiProtocols.imageRoute(activeProtocol, imageApi) != "chat_images") {
            require(ApiProtocols.imageRoute(activeProtocol, imageApi) != "unsupported") {
                "У выбранного протокола нет Image API. Выбери другой протокол подключения."
            }
            generateImages(context, activeProtocol, base, key, model, history, onText, onImage)
            return@withContext
        }
        val preparedHistory = history.map { message ->
            // Generated audio/video are outputs, not text-chat inputs on the next turn.
            val attachments = if (message.role == "assistant") message.attachments.filter { isImageMime(it.mimeType) } else message.attachments
            message to prepareAttachments(context, attachments)
        }
        val payload = JSONObject()
        val url: String
        val requestBuilder: Request.Builder
        val useResponses = activeProtocol == ApiProtocols.RESPONSES || (activeProtocol == ApiProtocols.OPENAI && (web || reasoning))

        when (activeProtocol) {
            ApiProtocols.ANTHROPIC -> {
                url = "$base/messages"
                payload.put("model", model).put("max_tokens", if (reasoning) maxOf(maxTokens, 2048) else maxTokens).put("stream", true)
                if (systemPrompt.isNotBlank()) payload.put("system", systemPrompt)
                if (reasoning) {
                    if (model.contains("4-5") || model.contains("4-0") || model.contains("3-")) {
                        payload.put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", 1024))
                    } else {
                        payload.put("thinking", JSONObject().put("type", "adaptive"))
                    }
                } else if (temperature != 1.0) payload.put("temperature", temperature.coerceAtMost(1.0))

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
                    thinking.put("includeThoughts", true)
                    config.put("thinkingConfig", thinking)
                } else if (model.startsWith("gemini-2.5-flash") && !model.contains("image") && !model.contains("tts") && !model.contains("live")) {
                    config.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                }
                payload.put("generationConfig", config)
                if (web) payload.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                requestBuilder = Request.Builder().header("x-goog-api-key", key)
            }

            ApiProtocols.OPENAI, ApiProtocols.RESPONSES -> {
                url = if (useResponses) "$base/responses" else "$base/chat/completions"
                payload.put("model", model).put("stream", if (useResponses) true else !imageMode)
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
                        inputs.put(JSONObject().put("role", m.role).put("content",
                            if (m.role == "assistant") messageTextWithFiles(m.text, prepared) else content))
                    }
                    payload.put("input", inputs)
                    if (web) payload.put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
                    if (reasoning) payload.put("reasoning", JSONObject().put("effort", "medium").put("summary", "auto"))
                    else if (model.substringAfterLast('/').matches(Regex("""gpt-5\.1(?:-\d{4}-\d{2}-\d{2})?"""))) {
                        payload.put("reasoning", JSONObject().put("effort", "none"))
                    }
                } else {
                    if (activeProtocol == ApiProtocols.OPENAI && ApiCapabilities.openAiReasoningModel(model)) {
                        payload.put("max_completion_tokens", maxTokens)
                    } else payload.put("temperature", temperature).put("max_tokens", maxTokens)
                    if (imageMode) payload.put("modalities", JSONArray().put("text").put("image"))
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
                payload.put("model", model).put("stream", !imageMode)
                if (activeProtocol == ApiProtocols.OPENAI && ApiCapabilities.openAiReasoningModel(model)) {
                        payload.put("max_completion_tokens", maxTokens)
                    } else payload.put("temperature", temperature).put("max_tokens", maxTokens)
                if (imageMode) payload.put("modalities", JSONArray().put("text").put("image"))
                val msgs = JSONArray()
                if (systemPrompt.isNotBlank()) msgs.put(JSONObject().put("role", "system").put("content", systemPrompt))
                preparedHistory.filter { it.first.text.isNotBlank() || it.second.isNotEmpty() }.forEach { (m, prepared) ->
                    msgs.put(JSONObject().put("role", m.role).put("content", openAiCompatibleContent(m.text, prepared)))
                }
                payload.put("messages", msgs)
                requestBuilder = Request.Builder()
                if (key.isNotBlank()) requestBuilder.header("Authorization", "Bearer $key")
                if (activeProtocol == ApiProtocols.OPENROUTER) requestBuilder.header("HTTP-Referer", "https://github.com/")
            }
        }

        if (!imageMode && (activeProtocol == ApiProtocols.OPENROUTER || activeProtocol == ApiProtocols.OPENAI_COMPAT)) {
            RouterFeatures.apply(payload, activeProtocol, web, reasoning, base, model, routerProfile, routerReasoning, reasoningFormat)
        }
        val unstreamedChatImage = imageMode && !imageApi && !useResponses && activeProtocol != ApiProtocols.GEMINI
        if (preset?.id == "onekey" && activeProtocol == ApiProtocols.ANTHROPIC) {
            requestBuilder.removeHeader("x-api-key")
            requestBuilder.header("Authorization", "Bearer $key")
        }
        val contentType = "application/json; charset=utf-8".toMediaType()
        val req = requestBuilder.url(url)
            .header("Accept", if (unstreamedChatImage) "application/json" else "text/event-stream")
            .post(payload.toString().toRequestBody(contentType))
            .build()

        val noRedirectClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val readResponse: suspend (Response) -> Unit = readResponse@{ response ->
                if (unstreamedChatImage || (response.header("Content-Type").orEmpty().contains("application/json") &&
                        activeProtocol in setOf(ApiProtocols.OPENAI, ApiProtocols.OPENAI_COMPAT, ApiProtocols.OPENROUTER) && !useResponses)) {
                    val raw = response.body?.string().orEmpty()
                    val obj = JSONObject(raw)
                    response.paymentFailure(obj)?.let { throw it }
                    if (obj.has("error")) throw IllegalStateException(obj.optJSONObject("error")?.optString("message") ?: "Ошибка API")
                    val choice = obj.optJSONArray("choices")?.optJSONObject(0)
                        ?: throw IllegalStateException("Модель не вернула изображение или текст")
                    consumeCompatibleChoice(context, choice, onText, onImage, onReasoning)
                    return@readResponse
                }
                val source = response.body?.source() ?: error("Пустой ответ API")
                val events = SseReader(source)
                var ended = false
                while (true) {
                    coroutineContext.ensureActive()
                    val raw = events.next() ?: break
                    if (raw == "[DONE]") { ended = true; break }
                    if (raw.isBlank()) continue
                    val obj = try { JSONObject(raw) } catch (failure: Exception) {
                        throw IllegalStateException("API вернул некорректное SSE-событие", failure)
                    }
                    response.paymentFailure(obj)?.let { throw it }
                    when (activeProtocol) {
                        ApiProtocols.ANTHROPIC -> when (obj.optString("type")) {
                            "content_block_start" -> {
                                val block = obj.optJSONObject("content_block")
                                if (block?.optString("type") == "thinking") block.optString("thinking").takeIf { it.isNotEmpty() }?.let { onReasoning(it) }
                                if (block?.optString("type") == "text") block.optString("text").takeIf { it.isNotEmpty() }?.let { onText(it) }
                            }
                            "content_block_delta" -> {
                                val delta = obj.optJSONObject("delta")
                                if (delta?.optString("type") == "thinking_delta") delta.optString("thinking").takeIf { it.isNotEmpty() }?.let { onReasoning(it) }
                                if (delta?.optString("type") == "text_delta") delta.optString("text").takeIf { it.isNotEmpty() }?.let { onText(it) }
                            }
                            "message_stop" -> ended = true
                            "message_delta" -> if (obj.optJSONObject("delta")?.optString("stop_reason") == "max_tokens")
                                throw IllegalStateException("Достигнут лимит токенов. Увеличь его в настройках модели.")
                            "error" -> throw IllegalStateException(obj.optJSONObject("error")?.optString("message") ?: "Ошибка Anthropic")
                        }
                        ApiProtocols.GEMINI -> {
                            val candidate = obj.optJSONArray("candidates")?.optJSONObject(0)
                            val finish = candidate?.optString("finishReason").orEmpty()
                            if (finish.isNotBlank()) ended = true
                            val blockReason = obj.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                            if (blockReason.isNotBlank()) throw IllegalStateException("Gemini отклонил запрос: $blockReason")
                            val parts = obj.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                            if (parts != null) {
                                for (i in 0 until parts.length()) {
                                    val part = parts.optJSONObject(i)
                                    part?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
                                        if (part.optBoolean("thought")) onReasoning(it) else onText(it)
                                    }
                                    part?.optJSONObject("inlineData")?.let { inline ->
                                        saveEncodedImage(context, inline.optString("data"), inline.optString("mimeType", "image/png"))?.let { onImage(it) }
                                    }
                                }
                            }
                            if (obj.has("error")) throw IllegalStateException(obj.optJSONObject("error")?.optString("message") ?: "Ошибка Gemini")
                            if (finish.isNotBlank() && finish != "STOP") throw IllegalStateException("Gemini остановил ответ: $finish")
                        }
                        else -> {
                            if (useResponses) {
                                when (obj.optString("type")) {
                                    "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> obj.optString("delta").takeIf { it.isNotEmpty() }?.let { onReasoning(it) }
                                    "response.output_text.delta" -> obj.optString("delta").takeIf { it.isNotEmpty() }?.let { onText(it) }
                                    "response.completed" -> ended = true
                                    "response.incomplete" -> throw IllegalStateException("Ответ прерван: " +
                                        obj.optJSONObject("response")?.optJSONObject("incomplete_details")?.optString("reason").orEmpty())
                                    "response.failed", "error" -> throw IllegalStateException(
                                        obj.optJSONObject("response")?.optJSONObject("error")?.optString("message")
                                            ?: obj.optJSONObject("error")?.optString("message")
                                            ?: "Ошибка OpenAI Responses"
                                    )
                                }
                            } else {
                                val first = obj.optJSONArray("choices")?.optJSONObject(0)
                                if (first != null) {
                                    consumeCompatibleChoice(context, first, onText, onImage, onReasoning)
                                }
                                if (first != null && first.has("finish_reason") && !first.isNull("finish_reason")) {
                                    ended = true
                                    when (first.optString("finish_reason")) {
                                        "length" -> throw IllegalStateException("Достигнут лимит токенов. Полученная часть сохранена.")
                                        "content_filter" -> throw IllegalStateException("Провайдер остановил ответ своим фильтром")
                                        "tool_calls", "function_call" -> throw IllegalStateException("Модель запросила внешний инструмент, который не подключён в этом чате")
                                    }
                                }
                                if (obj.has("error")) throw IllegalStateException(obj.optJSONObject("error")?.optString("message") ?: "Ошибка API")
                            }
                        }
                    }
                    if (ended) break
                }
                if (!ended) throw IllegalStateException("Соединение прервалось до завершения ответа. Полученная часть сохранена.")
        }
        val retry = noRedirectClient.newCall(req).consume { response ->
            if (response.isSuccessful) {
                readResponse(response)
                return@consume null
            }
            val failure = response.apiFailure()
            val affordable = failure.affordableTokens ?: throw failure
            if (imageMode || activeProtocol == ApiProtocols.GEMINI) throw failure
            val field = listOf("max_tokens", "max_completion_tokens", "max_output_tokens").firstOrNull(payload::has)
                ?: throw failure
            val minimum = if (useResponses) 16 else (payload.optJSONObject("thinking")?.optInt("budget_tokens") ?: 0) + 1
            if (affordable < minimum || affordable >= payload.getInt(field)) throw failure
            // The first request was rejected before streaming. Retry once on the same endpoint with only the cap reduced.
            req.newBuilder().post(payload.put(field, affordable).toString().toRequestBody(contentType)).build()
        }
        if (retry != null) {
            coroutineContext.ensureActive()
            noRedirectClient.newCall(retry).consume { response ->
                if (!response.isSuccessful) throw response.apiFailure()
                readResponse(response)
            }
        }
    }

    private suspend fun consumeCompatibleChoice(
        context: Context, choice: JSONObject,
        onText: suspend (String) -> Unit,
        onImage: suspend (Attachment) -> Unit,
        onReasoning: suspend (String) -> Unit
    ) {
        val message = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return
        val thought = (message.opt("reasoning") as? String)?.takeIf { it.isNotEmpty() }
            ?: (message.opt("reasoning_content") as? String)?.takeIf { it.isNotEmpty() }
        if (thought != null) onReasoning(thought)
        else {
            val details = message.optJSONArray("reasoning_details") ?: JSONArray()
            for (i in 0 until details.length()) {
                val detail = details.optJSONObject(i) ?: continue
                if (detail.optString("type") in setOf("reasoning.text", "reasoning.summary")) {
                    detail.optString("text").ifEmpty { detail.optString("summary") }.takeIf { it.isNotEmpty() }?.let { onReasoning(it) }
                }
            }
        }
        val parts = message.optJSONArray("content")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                when (part.optString("type")) {
                    "text", "output_text" -> part.optString("text").takeIf { it.isNotEmpty() }?.let { onText(it) }
                    "image", "image_url" -> consumeImageValue(
                        context,
                        part.optJSONObject("image_url")?.optString("url")
                            ?: part.optString("image_url").ifBlank { part.optString("url") },
                        onText, onImage
                    )
                }
            }
        } else {
            (message.opt("content") as? String)?.takeIf { it.isNotEmpty() }?.let { onText(it) }
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
        require(last?.attachments.orEmpty().all { isImageMime(it.mimeType) }) { "Для генерации изображения прикрепляй только изображения" }
        if (protocol == ApiProtocols.OPENROUTER) require(last?.attachments.orEmpty().size <= 1) {
            "Для этого Image API поддерживается одно исходное изображение"
        }
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
                require(last?.attachments.isNullOrEmpty()) { "Этот маршрут Image API принимает текст. Для редактирования по изображению выбери совместимую Chat Image модель." }
                url = "$base/images/generations"
                if (key.isNotBlank()) builder.header("Authorization", "Bearer $key")
            }
            ApiProtocols.OPENAI, ApiProtocols.RESPONSES -> {
                require(last?.attachments.isNullOrEmpty()) { "Этот маршрут генерирует по тексту. Для работы с изображением выбери модель с поддержкой Chat Image." }
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
        call.consume { response ->
                if (!response.isSuccessful) throw response.apiFailure()
                val raw = response.body?.string().orEmpty()
                val json = JSONObject(raw)
                response.paymentFailure(json)?.let { throw it }
                var count = 0
                if (gemini) {
                    val parts = json.optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                    for (i in 0 until parts.length()) {
                        coroutineContext.ensureActive()
                        val part = parts.optJSONObject(i) ?: continue
                        part.optString("text").takeIf { it.isNotEmpty() }?.let { onText(it) }
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
            val content = item.textContent
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
        return attachments.map { attachment ->
            val uri = Uri.parse(attachment.uri)
            if (isImageMime(attachment.mimeType)) {
                val bytes = readBytes(context, uri, 8 * 1024 * 1024)
                    ?: throw IllegalArgumentException("Не удалось открыть ${attachment.name}. Прикрепи файл заново.")
                PreparedAttachment(attachment, bytes = bytes)
            } else {
                val text = readTextIfPossible(context, uri, attachment.mimeType)
                    ?: throw IllegalArgumentException("${attachment.name}: этот формат нельзя отправить в текстовый чат. Используй текстовый файл, изображение или режим расшифровки аудио.")
                PreparedAttachment(attachment, textContent = text)
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
            val buffer = CharArray(32_001)
            var length = 0
            while (length < buffer.size) {
                val count = reader.read(buffer, length, buffer.size - length)
                if (count < 0) break
                length += count
            }
            require(length <= 32_000) { "Текстовое вложение превышает 32000 символов. Раздели файл на части." }
            String(buffer, 0, length)
        }
    }
}
