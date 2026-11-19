package com.neura.app

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class CatalogModel(
    val id: String,
    val name: String,
    val description: String = "",
    val context: Int = 0,
    val inputImage: Boolean = false,
    val outputImage: Boolean = false,
    val imageApi: Boolean = false,
    val reasoning: Boolean = false,
    val reasoningMandatory: Boolean = false,
    val defaultReasoningEffort: String? = null,
    val web: Boolean = false,
    val free: Boolean = false,
    val pricePrompt: Double? = null,
    val priceCompletion: Double? = null,
    val created: Long = 0L,
    val outputModalities: Set<String> = emptySet(),
    val route: String = MediaRoute.CHAT,
    val supportedVoices: List<String> = emptyList()
)

data class CatalogResult(val models: List<CatalogModel>, val notice: String = "")

/**
 * Model discovery is independent from inference.
 * In particular, OpenRouter's public model indexes MUST NOT receive the user's inference key.
 * Index metadata never proves that a model is usable with a particular key.
 */
class ModelCatalog(private val directory: File, private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()) {
    constructor(context: Context) : this(context.filesDir)

    suspend fun list(provider: String, endpoint: String, key: String, protocol: String = ApiProtocols.defaultFor(provider)): CatalogResult = withContext(Dispatchers.IO) {
        val base = ApiConnection.baseUrl(endpoint)
        checkSecureEndpoint(base)
        if (protocol == ApiProtocols.OPENROUTER) {
            return@withContext loadOpenRouter(provider, base, key)
        }
        val format = ApiProtocols.modelCatalogFormat(protocol)
        val cacheFile = cacheFile("$provider|$protocol", ApiConnection.identity(protocol, base, key))
        try {
            val json = requestPages(base, format, key.trim())
            val parsed = CatalogParser.parse(format, json, false).sortedBy { it.name.lowercase() }
            if (parsed.isEmpty()) throw IllegalStateException("Сервер не вернул список моделей")
            runCatching { saveCache(cacheFile, JSONObject().put("models", json)) }
            CatalogResult(parsed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is ApiHttpException && error.status in setOf(401, 403)) throw error
            val cached = loadCache(cacheFile)?.optJSONObject("models")
            val parsed = cached?.let { CatalogParser.parse(format, it, false) }.orEmpty()
            if (parsed.isNotEmpty()) {
                CatalogResult(parsed.sortedBy { it.name.lowercase() },
                    "Не удалось обновить каталог. Показан ранее сохранённый список. ${error.message.orEmpty()}")
            } else {
                throw IllegalStateException(catalogError(provider, error), error)
            }
        }
    }

    private suspend fun loadOpenRouter(provider: String, base: String, key: String): CatalogResult {
        // Query the configured server, not OpenRouter's public index for a custom proxy.
        // Official OpenRouter catalogue is public; custom proxies may require auth.
        val publicIndex = provider == "openrouter" && base == "https://openrouter.ai/api/v1"
        val listingAuth = if (publicIndex) "openrouter" else "openrouter-images"
        val listingKey = if (publicIndex) "" else key
        val cacheFile = cacheFile("$provider|${ApiProtocols.OPENROUTER}", ApiConnection.identity(ApiProtocols.OPENROUTER, base, key))
        val cached = loadCache(cacheFile)

        // A public list of all models, independent of user credits or model access.
        val all = catalogAttempt {
            catalogAttempt { requestJson("$base/models?output_modalities=all", listingAuth, listingKey) }
                .getOrElse { requestJson("$base/models", listingAuth, listingKey) }
        }
        // Public route-specific discovery helps classify dedicated Image API models.
        val imageRoutes = catalogAttempt {
            requestJson("$base/models?output_modalities=image", listingAuth, listingKey)
        }
        // Unlike the public /models endpoint, /images/models documents Bearer auth.
        // This call may fail with 401/403; it must never hide the public catalog.
        val imageDetails = if (key.isNotBlank()) catalogAttempt {
            requestJson("$base/images/models", "openrouter-images", key)
        } else null

        val videoDetails = if (key.isNotBlank()) catalogAttempt {
            requestJson("$base/videos/models", "openrouter-images", key)
        } else null

        val listingError = all.exceptionOrNull()
        if (listingError is ApiHttpException && listingError.status in setOf(401, 403)) throw listingError

        val allJson = all.getOrNull() ?: cached?.optJSONObject("all")
            ?: cached?.optJSONObject("models") // Migration from v5.1 text-only cache.
        val routeJson = imageRoutes.getOrNull() ?: cached?.optJSONObject("imageRoutes")
        val imageJson = imageDetails?.getOrNull() ?: cached?.optJSONObject("images")
        val videoJson = videoDetails?.getOrNull() ?: cached?.optJSONObject("videos")

        val allModels = allJson?.let { CatalogParser.parse("openrouter", it, false) }.orEmpty()
        // The image modality list is not synonymous with Image API: some models
        // produce images through /chat/completions. Inspect each model's endpoints.
        val routes = routeJson?.let { CatalogParser.parse("openrouter", it, false) }.orEmpty()
        val images = imageJson?.let { CatalogParser.parse("openrouter", it, true) }.orEmpty()
        val videos = videoJson?.let { CatalogParser.parse("openrouter", it, false, true) }.orEmpty()
        val variants = allModels + routes + images + videos

        // The same ID can be discovered by all three endpoints. Preserve actual
        // capabilities from each and prefer canonical metadata from /models?all.
        val models = variants.groupBy { it.id }.map { (_, entries) ->
            val canonical = entries.first()
            canonical.copy(
                description = entries.firstNotNullOfOrNull { it.description.takeIf(String::isNotBlank) } ?: "",
                context = entries.maxOf { it.context },
                inputImage = entries.any { it.inputImage },
                outputImage = entries.any { it.outputImage },
                imageApi = entries.any { it.imageApi },
                reasoning = entries.any { it.reasoning },
                reasoningMandatory = entries.any { it.reasoningMandatory },
                defaultReasoningEffort = entries.firstNotNullOfOrNull { it.defaultReasoningEffort },
                web = entries.any { it.web },
                free = entries.any { it.free },
                pricePrompt = entries.firstNotNullOfOrNull { it.pricePrompt },
                priceCompletion = entries.firstNotNullOfOrNull { it.priceCompletion },
                created = entries.maxOf { it.created },
                outputModalities = entries.flatMap { it.outputModalities }.toSet(),
                supportedVoices = entries.flatMap { it.supportedVoices }.distinct(),
                route = entries.firstOrNull { it.route == MediaRoute.VIDEO }?.route
                    ?: entries.firstOrNull { it.route == MediaRoute.SPEECH || it.route == MediaRoute.TRANSCRIPTION }?.route
                    ?: entries.firstOrNull { it.route == MediaRoute.IMAGE }?.route
                    ?: entries.firstOrNull { it.route == MediaRoute.CHAT }?.route
                    ?: MediaRoute.UNSUPPORTED
            )
        }.sortedBy { it.name.lowercase() }

        if (models.isEmpty()) {
            val details = listOfNotNull(all.exceptionOrNull()?.message,
                imageRoutes.exceptionOrNull()?.message,
                imageDetails?.exceptionOrNull()?.message,
                videoDetails?.exceptionOrNull()?.message).distinct().joinToString("; ")
            throw IllegalStateException("Не удалось загрузить модели OpenRouter. " +
                (details.ifBlank { "Провайдер вернул пустой каталог" }).take(240))
        }

        // Keep every successful source independently. Transient network errors
        // must not overwrite previously cached results with empty content.
        if (all.isSuccess || imageRoutes.isSuccess || imageDetails?.isSuccess == true || videoDetails?.isSuccess == true) {
            val combined = JSONObject()
            (all.getOrNull() ?: cached?.optJSONObject("all") ?: cached?.optJSONObject("models"))?.let { combined.put("all", it) }
            (imageRoutes.getOrNull() ?: cached?.optJSONObject("imageRoutes"))?.let { combined.put("imageRoutes", it) }
            (imageDetails?.getOrNull() ?: cached?.optJSONObject("images"))?.let { combined.put("images", it) }
            (videoDetails?.getOrNull() ?: cached?.optJSONObject("videos"))?.let { combined.put("videos", it) }
            runCatching { saveCache(cacheFile, combined) }
        }

        val notice = when {
            all.isFailure && cached?.optJSONObject("all") == null ->
                "Полный каталог пока недоступен. Показан частичный список: ${all.exceptionOrNull()?.message.orEmpty().take(100)}"
            all.isFailure -> "Нет связи с полным каталогом. Показан сохранённый список."
            else -> ""
        }
        return CatalogResult(models, notice)
    }

    private fun catalogError(provider: String, error: Exception): String {
        val original = error.message.orEmpty()
        return when {
            error is ApiHttpException && error.status == 401 ->
                "Каталог ${Providers.name(provider)} требует действующий API-ключ (401)."
            error is ApiHttpException && error.status == 403 ->
                "Провайдер ${Providers.name(provider)} запретил запрос списка моделей (403). " +
                    "Это не означает, что конкретная модель недоступна."
            error is ApiHttpException && error.status == 404 ->
                "Этот API не предоставляет каталог по адресу /models (404). " +
                    "Можно указать ID модели вручную."
            else -> "Не удалось загрузить каталог: ${original.take(180)}"
        }
    }

    private suspend fun requestJson(url: String, provider: String, key: String): JSONObject {
        val builder = Request.Builder().get().url(url).header("Accept", "application/json")
        when (provider) {
            "anthropic" -> {
                if (key.isNotBlank()) builder.header("x-api-key", key)
                builder.header("anthropic-version", "2023-06-01")
            }
            "gemini" -> if (key.isNotBlank()) builder.header("x-goog-api-key", key)
            "openrouter" -> Unit // The public /models catalogue needs no key.
            "openrouter-images" -> if (key.isNotBlank()) builder.header("Authorization", "Bearer $key")
            else -> if (key.isNotBlank()) builder.header("Authorization", "Bearer $key")
        }
        if (RouterPresets.detect(url)?.id == "onekey" && key.isNotBlank()) {
            builder.removeHeader("x-api-key").header("Authorization", "Bearer $key")
        }
        return client.newCall(builder.build()).consume { response ->
            if (!response.isSuccessful) throw response.apiFailure()
            val raw = response.body?.string().orEmpty()
            val trimmed = raw.trim()
            if (trimmed.startsWith("[")) JSONObject().put("data", JSONArray(trimmed)) else JSONObject(trimmed)
        }
    }

    private suspend fun requestPages(base: String, format: String, key: String): JSONObject {
        val field = if (format == "gemini") "models" else "data"
        val combined = JSONArray()
        val cursors = mutableSetOf<String>()
        var url = "$base/models"
        repeat(100) {
            coroutineContext.ensureActive()
            val page = requestJson(url, format, key)
            val data = page.optJSONArray(field) ?: page.optJSONArray("models") ?: JSONArray()
            for (i in 0 until data.length()) combined.put(data.get(i))
            val cursor = if (format == "gemini") page.optString("nextPageToken")
                else if (page.optBoolean("has_more")) page.optString("last_id").ifBlank {
                    data.optJSONObject(data.length() - 1)?.optString("id").orEmpty()
                } else ""
            if (cursor.isBlank()) return JSONObject().put(field, combined)
            require(cursors.add(cursor)) { "API повторяет курсор каталога" }
            url = "$base/models".toHttpUrl().newBuilder()
                .addQueryParameter(if (format == "gemini") "pageToken" else "after_id", cursor).build().toString()
        }
        error("Каталог превышает 100 страниц")
    }

    private fun cacheFile(provider: String, endpoint: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$provider|$endpoint".toByteArray(Charsets.UTF_8))
            .take(10).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(directory, "model_catalog_$digest.json")
    }

    private fun loadCache(file: File): JSONObject? = runCatching {
        if (!file.exists()) return@runCatching null
        JSONObject(file.readText(Charsets.UTF_8))
    }.getOrNull()

    private fun saveCache(file: File, json: JSONObject) {
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

}

internal object CatalogParser {
    fun parse(provider: String, json: JSONObject, imageApi: Boolean, videoApi: Boolean = false): List<CatalogModel> {
        val data = json.optJSONArray(if (provider == "gemini") "models" else "data") ?: JSONArray()
        return (0 until data.length()).mapNotNull { i ->
            val model = data.optJSONObject(i) ?: return@mapNotNull null
            val id = model.optString("id").ifBlank { model.optString("name").removePrefix("models/") }
            if (id.isBlank()) return@mapNotNull null
            val architecture = model.optJSONObject("architecture")
            val inputModalities = (architecture?.optJSONArray("input_modalities") ?: model.optJSONArray("input_modalities")).toSetStrings()
            val outputModalities = (architecture?.optJSONArray("output_modalities") ?: model.optJSONArray("output_modalities")).toSetStrings()
                .ifEmpty { if (videoApi) setOf("video") else emptySet() }
            val params = model.optJSONArray("supported_parameters").toSetStrings()
            val pricing = model.optJSONObject("pricing")
            val priceIn = pricing?.optString("prompt")?.toDoubleOrNull()
            val priceOut = pricing?.optString("completion")?.toDoubleOrNull()
            val inputImage = inputModalities.contains("image") || (provider == "gemini" && id.startsWith("gemini-"))
            val imageOnlyId = (provider == "openai" && (id.startsWith("gpt-image-") || id.startsWith("chatgpt-image") || id.startsWith("dall-e-"))) ||
                (provider == "gemini" && (id.contains("-image") || id.contains("imagen")))
            val outputImage = imageApi || outputModalities.contains("image") || imageOnlyId
            val endpointPaths = model.optJSONArray("endpoints").toSetStrings()
            val supportsImageEndpoint = endpointPaths.any { it == "/api/v1/images" || it.endsWith("/images") }
            // Do not mark paid image models as free just because token pricing is zero.
            // The Image API may charge per request, output image or megapixel.
            val paidFields = listOf("prompt", "completion", "request", "image", "output", "input", "image_output", "image_token", "input_image")
            val anyPaidCharge = pricing != null && paidFields.any { field ->
                pricing.optString(field).toDoubleOrNull()?.let { it > 0.0 } ?: false
            }
            // Media often charges per image, audio minute, or video second, even if token prices are zero.
            val mediaProduct = videoApi || outputImage || outputModalities.any {
                it in setOf("video", "audio", "speech", "transcription", "music")
            } || model.has("pricing_skus")
            val isFree = id.endsWith(":free") || (provider == "openrouter" && !mediaProduct &&
                priceIn == 0.0 && priceOut == 0.0 && !anyPaidCharge)
            val searchText = (id + " " + model.optString("name")).lowercase()
            val methods = model.optJSONArray("supportedGenerationMethods").toSetStrings()
            val specialized = (provider == "gemini" && (id.startsWith("imagen-") || id.startsWith("veo-"))) || id.contains("embedding", true) || id.contains("rerank", true) || id.contains("moderation", true) ||
                (provider == "gemini" && methods.isNotEmpty() && methods.none { it in setOf("generateContent", "predict", "predictLongRunning") })
            val isTranscription = ("transcription" in outputModalities) ||
                searchText.contains("speech-to-text") || searchText.contains("transcription") ||
                searchText.contains("transcribe") || searchText.contains("whisper") ||
                (id.startsWith("assemblyai/") && searchText.contains("universal"))
            val isSpeech = ("speech" in outputModalities) || searchText.contains("text-to-speech") ||
                searchText.contains("tts") || searchText.contains("speech synthesis")
            val nativeGeminiVideo = provider == "gemini" && (id.startsWith("veo-") || id.startsWith("gemini-omni-"))
            val nativeGeminiAudio = provider == "gemini" && id.contains("tts")
            val route = when {
                specialized -> MediaRoute.UNSUPPORTED
                videoApi || "video" in outputModalities || nativeGeminiVideo || (provider == "openai" && id.startsWith("sora-")) -> MediaRoute.VIDEO
                imageApi || outputImage -> MediaRoute.IMAGE
                isTranscription -> MediaRoute.TRANSCRIPTION
                isSpeech || nativeGeminiAudio -> MediaRoute.SPEECH
                outputModalities.any { it in setOf("embeddings", "rerank", "decisions", "audio", "music") } -> MediaRoute.UNSUPPORTED
                outputModalities.isEmpty() || "text" in outputModalities -> MediaRoute.CHAT
                else -> MediaRoute.UNSUPPORTED
            }
            CatalogModel(
                id = id,
                name = model.optString("displayName").ifBlank { model.optString("display_name").ifBlank { model.optString("name").ifBlank { id } } },
                description = model.optString("description"),
                context = model.optInt("context_length", model.optInt("inputTokenLimit", 0)),
                inputImage = inputImage,
                outputImage = outputImage,
                imageApi = imageApi || supportsImageEndpoint || (provider == "openai" && imageOnlyId),
                reasoning = params.any { it.contains("reasoning") } || model.optBoolean("reasoning", false) || model.optJSONObject("reasoning") != null,
                reasoningMandatory = model.optJSONObject("reasoning")?.optBoolean("mandatory") == true,
                defaultReasoningEffort = model.optJSONObject("reasoning")?.optString("default_effort")
                    ?.takeIf { it in setOf("minimal", "low", "medium", "high", "xhigh", "max") },
                web = params.any { it.contains("web") },
                free = isFree,
                pricePrompt = priceIn,
                priceCompletion = priceOut,
                created = model.optLong("created", 0L),
                route = route,
                supportedVoices = model.optJSONArray("supported_voices").toSetStrings().toList(),
                outputModalities = outputModalities.ifEmpty {
                    when {
                        nativeGeminiVideo -> setOf("video")
                        nativeGeminiAudio -> setOf("audio")
                        outputImage -> setOf("image")
                        provider == "openrouter" -> emptySet()
                        else -> setOf("text")
                    }
                }
            )
        }.distinctBy { it.id }
    }
}

private suspend fun <T> catalogAttempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}

private fun JSONArray?.toSetStrings(): Set<String> {
    if (this == null) return emptySet()
    return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }.toSet()
}

fun checkSecureEndpoint(base: String) { ApiConnection.baseUrl(base) }
