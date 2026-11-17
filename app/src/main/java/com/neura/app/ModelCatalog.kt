package com.neura.app

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
    val web: Boolean = false,
    val free: Boolean = false,
    val pricePrompt: Double? = null,
    val priceCompletion: Double? = null,
    val created: Long = 0L,
    val outputModalities: Set<String> = emptySet()
)

data class CatalogResult(val models: List<CatalogModel>, val notice: String = "")

/**
 * Model discovery is independent from inference.
 * In particular, OpenRouter's public model indexes MUST NOT receive the user's inference key.
 * Index metadata never proves that a model is usable with a particular key.
 */
class ModelCatalog(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun list(provider: String, endpoint: String, key: String): CatalogResult = withContext(Dispatchers.IO) {
        if (provider == "openrouter") {
            return@withContext loadOpenRouter(key)
        }

        val base = endpoint.trim().trimEnd('/')
        checkSecureEndpoint(base)
        val cacheFile = cacheFile(provider, base)
        try {
            val json = requestJson("$base/models", provider, key)
            val parsed = parseModels(provider, json, false).sortedBy { it.name.lowercase() }
            if (parsed.isEmpty()) throw IllegalStateException("Сервер не вернул список моделей")
            saveCache(cacheFile, JSONObject().put("models", json))
            CatalogResult(parsed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val cached = loadCache(cacheFile)?.optJSONObject("models")
            val parsed = cached?.let { parseModels(provider, it, false) }.orEmpty()
            if (parsed.isNotEmpty()) {
                CatalogResult(parsed.sortedBy { it.name.lowercase() },
                    "Не удалось обновить каталог. Показан ранее сохранённый список. ${error.message.orEmpty()}")
            } else {
                throw IllegalStateException(catalogError(provider, error), error)
            }
        }
    }

    private fun loadOpenRouter(key: String): CatalogResult {
        // The normal /models endpoint is TEXT ONLY by default. `all` is required
        // to discover image, video, audio, embedding and other output modalities.
        val base = "https://openrouter.ai/api/v1"
        val cacheFile = cacheFile("openrouter", base)
        val cached = loadCache(cacheFile)

        // A public list of all models, independent of user credits or model access.
        val all = runCatching { requestJson("$base/models?output_modalities=all", "openrouter", "") }
        // Public route-specific discovery helps classify dedicated Image API models.
        val imageRoutes = runCatching {
            requestJson("$base/models?output_modalities=image", "openrouter", "")
        }
        // Unlike the public /models endpoint, /images/models documents Bearer auth.
        // This call may fail with 401/403; it must never hide the public catalog.
        val imageDetails = if (key.isNotBlank()) runCatching {
            requestJson("$base/images/models", "openrouter-images", key)
        } else null

        val allJson = all.getOrNull() ?: cached?.optJSONObject("all")
            ?: cached?.optJSONObject("models") // Migration from v5.1 text-only cache.
        val routeJson = imageRoutes.getOrNull() ?: cached?.optJSONObject("imageRoutes")
        val imageJson = imageDetails?.getOrNull() ?: cached?.optJSONObject("images")

        val allModels = allJson?.let { parseModels("openrouter", it, false) }.orEmpty()
        // The image modality list is not synonymous with Image API: some models
        // produce images through /chat/completions. Inspect each model's endpoints.
        val routes = routeJson?.let { parseModels("openrouter", it, false) }.orEmpty()
        val images = imageJson?.let { parseModels("openrouter", it, true) }.orEmpty()
        val variants = allModels + routes + images

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
                web = entries.any { it.web },
                free = entries.any { it.free },
                pricePrompt = entries.firstNotNullOfOrNull { it.pricePrompt },
                priceCompletion = entries.firstNotNullOfOrNull { it.priceCompletion },
                created = entries.maxOf { it.created },
                outputModalities = entries.flatMap { it.outputModalities }.toSet()
            )
        }.sortedBy { it.name.lowercase() }

        if (models.isEmpty()) {
            val details = listOfNotNull(all.exceptionOrNull()?.message,
                imageRoutes.exceptionOrNull()?.message,
                imageDetails?.exceptionOrNull()?.message).distinct().joinToString("; ")
            throw IllegalStateException("Не удалось загрузить модели OpenRouter. " +
                (details.ifBlank { "Провайдер вернул пустой каталог" }).take(240))
        }

        // Keep every successful source independently. Transient network errors
        // must not overwrite previously cached results with empty content.
        if (all.isSuccess || imageRoutes.isSuccess || imageDetails?.isSuccess == true) {
            val combined = JSONObject()
            (all.getOrNull() ?: cached?.optJSONObject("all") ?: cached?.optJSONObject("models"))?.let { combined.put("all", it) }
            (imageRoutes.getOrNull() ?: cached?.optJSONObject("imageRoutes"))?.let { combined.put("imageRoutes", it) }
            (imageDetails?.getOrNull() ?: cached?.optJSONObject("images"))?.let { combined.put("images", it) }
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
            original.contains("HTTP 401") ->
                "Каталог ${Providers.name(provider)} требует действующий API-ключ (401)."
            original.contains("HTTP 403") ->
                "Провайдер ${Providers.name(provider)} запретил запрос списка моделей (403). " +
                    "Это не означает, что конкретная модель недоступна."
            original.contains("HTTP 404") ->
                "Этот API не предоставляет каталог по адресу /models (404). " +
                    "Можно указать ID модели вручную."
            else -> "Не удалось загрузить каталог: ${original.take(180)}"
        }
    }

    private fun requestJson(url: String, provider: String, key: String): JSONObject {
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
        client.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }
                    .getOrNull()?.takeIf { it.isNotBlank() }.orEmpty()
                throw IllegalStateException("HTTP ${response.code}${if (message.isBlank()) "" else ": ${message.take(160)}"}")
            }
            return JSONObject(raw)
        }
    }

    private fun cacheFile(provider: String, endpoint: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$provider|$endpoint".toByteArray(Charsets.UTF_8))
            .take(10).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(context.filesDir, "model_catalog_$digest.json")
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

    private fun parseModels(provider: String, json: JSONObject, imageApi: Boolean): List<CatalogModel> {
        val data = json.optJSONArray(if (provider == "gemini") "models" else "data") ?: JSONArray()
        return (0 until data.length()).mapNotNull { i ->
            val model = data.optJSONObject(i) ?: return@mapNotNull null
            val id = model.optString("id").ifBlank { model.optString("name").removePrefix("models/") }
            if (id.isBlank()) return@mapNotNull null
            val architecture = model.optJSONObject("architecture")
            val inputModalities = architecture?.optJSONArray("input_modalities").toSetStrings()
            val outputModalities = architecture?.optJSONArray("output_modalities").toSetStrings()
            val params = model.optJSONArray("supported_parameters").toSetStrings()
            val pricing = model.optJSONObject("pricing")
            val priceIn = pricing?.optString("prompt")?.toDoubleOrNull()
            val priceOut = pricing?.optString("completion")?.toDoubleOrNull()
            val inputImage = inputModalities.contains("image") || (provider == "gemini" && id.startsWith("gemini-"))
            val imageOnlyId = (provider == "openai" && (id.startsWith("gpt-image-") || id.startsWith("chatgpt-image"))) ||
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
            val isFree = id.endsWith(":free") || (provider == "openrouter" && priceIn == 0.0 &&
                priceOut == 0.0 && !anyPaidCharge)
            CatalogModel(
                id = id,
                name = model.optString("name").ifBlank { model.optString("display_name").ifBlank { id } },
                description = model.optString("description"),
                context = model.optInt("context_length", model.optInt("inputTokenLimit", 0)),
                inputImage = inputImage,
                outputImage = outputImage,
                imageApi = imageApi || supportsImageEndpoint || (provider == "openai" && imageOnlyId),
                reasoning = params.any { it.contains("reasoning") } || model.optBoolean("reasoning", false) || model.has("reasoning"),
                web = params.any { it.contains("web") },
                free = isFree,
                pricePrompt = priceIn,
                priceCompletion = priceOut,
                created = model.optLong("created", 0L),
                outputModalities = outputModalities.ifEmpty {
                    when {
                        outputImage -> setOf("image")
                        provider == "openrouter" -> emptySet()
                        else -> setOf("text")
                    }
                }
            )
        }
    }
}

private fun JSONArray?.toSetStrings(): Set<String> {
    if (this == null) return emptySet()
    return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }.toSet()
}

fun checkSecureEndpoint(base: String) {
    val uri = java.net.URI(base)
    require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null && uri.fragment == null && uri.query == null) {
        "Нужен корректный HTTPS API endpoint без логина или параметров"
    }
}
