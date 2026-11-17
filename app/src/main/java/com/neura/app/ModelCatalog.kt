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
    val created: Long = 0L
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
            return@withContext loadOpenRouter()
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

    private fun loadOpenRouter(): CatalogResult {
        // The public endpoints work without an API key; never send Authorization here.
        // The base is deliberately canonical so an incorrectly configured inference endpoint
        // cannot break discovery of the public OpenRouter model index.
        val base = "https://openrouter.ai/api/v1"
        val cacheFile = cacheFile("openrouter", base)
        val cached = loadCache(cacheFile)
        val chat = runCatching { requestJson("$base/models", "openrouter", "") }
        val images = runCatching { requestJson("$base/images/models", "openrouter", "") }

        val chatJson = chat.getOrNull() ?: cached?.optJSONObject("models")
        val imageJson = images.getOrNull() ?: cached?.optJSONObject("images")
        val fromChat = chatJson?.let { parseModels("openrouter", it, false) }.orEmpty()
        val fromImages = imageJson?.let { parseModels("openrouter", it, true) }.orEmpty()
        val result = (fromChat + fromImages).groupBy { it.id }.map { (_, variants) ->
            val text = variants.first()
            val image = variants.firstOrNull { it.imageApi }
            if (image == null) text else image.copy(
                description = image.description.ifBlank { text.description },
                inputImage = image.inputImage || text.inputImage,
                outputImage = true,
                reasoning = text.reasoning,
                web = text.web,
                free = image.free || text.free,
                context = if (image.context > 0) image.context else text.context
            )
        }.sortedBy { it.name.lowercase() }

        if (result.isEmpty()) {
            val details = listOfNotNull(chat.exceptionOrNull()?.message, images.exceptionOrNull()?.message)
                .distinct().joinToString("; ")
            throw IllegalStateException(
                "Не удалось загрузить публичный каталог OpenRouter. " +
                    (if (details.isBlank()) "Сервер вернул пустой список." else details) +
                    " Это не означает, что у ключа нет доступа к моделям."
            )
        }

        // Keep the last successful JSON for each catalog separately. An outage in one endpoint
        // should not delete working cached data from the other endpoint.
        if (chat.isSuccess || images.isSuccess) {
            val combined = JSONObject()
            if (chatJson != null) combined.put("models", chatJson)
            if (imageJson != null) combined.put("images", imageJson)
            runCatching { saveCache(cacheFile, combined) }
        }

        val notice = when {
            chat.isFailure && images.isFailure ->
                "Нет связи с OpenRouter. Показан ранее сохранённый каталог."
            chat.isFailure ->
                "Текстовый каталог OpenRouter временно недоступен; часть моделей может отсутствовать."
            images.isFailure ->
                "Список генераторов изображений временно недоступен; остальные модели загружены."
            else -> ""
        }
        return CatalogResult(result, notice)
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
            "openrouter" -> Unit // Public catalogue, no key sent.
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
            val isFree = id.endsWith(":free") || (provider == "openrouter" && priceIn == 0.0 && priceOut == 0.0 && pricing != null)
            CatalogModel(
                id = id,
                name = model.optString("name").ifBlank { model.optString("display_name").ifBlank { id } },
                description = model.optString("description"),
                context = model.optInt("context_length", model.optInt("inputTokenLimit", 0)),
                inputImage = inputImage,
                outputImage = outputImage,
                imageApi = imageApi || (provider == "openai" && imageOnlyId),
                reasoning = params.any { it.contains("reasoning") } || model.optBoolean("reasoning", false) || model.has("reasoning"),
                web = params.any { it.contains("web") },
                free = isFree,
                pricePrompt = priceIn,
                priceCompletion = priceOut,
                created = model.optLong("created", 0L)
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
