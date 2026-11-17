package com.neura.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
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

class ModelCatalog {
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).build()

    suspend fun list(provider: String, endpoint: String, key: String): List<CatalogModel> = withContext(Dispatchers.IO) {
        val base = endpoint.trim().trimEnd('/')
        checkSecureEndpoint(base)
        if (provider == "openrouter") {
            // The regular endpoint defaults to text output, hence the second dedicated image catalog.
            val chat = fetch("$base/models?output_modalities=all", provider, key)
            val images = runCatching { fetch("$base/images/models", provider, key) }.getOrDefault(JSONObject())
            val fromChat = parseModels(provider, chat, false)
            val fromImages = parseModels(provider, images, true)
            return@withContext (fromChat + fromImages).groupBy { it.id }.map { (_, variants) ->
                // Image API entries take precedence; retain input metadata from chat listing.
                val image = variants.firstOrNull { it.imageApi }
                val text = variants.first()
                if (image != null) image.copy(
                    description = image.description.ifBlank { text.description },
                    inputImage = image.inputImage || text.inputImage,
                    outputImage = true,
                    reasoning = text.reasoning,
                    web = text.web
                ) else text
            }.sortedBy { it.name.lowercase() }
        }
        val json = fetch("$base/models", provider, key)
        parseModels(provider, json, false).sortedBy { it.name.lowercase() }
    }

    private fun fetch(url: String, provider: String, key: String): JSONObject {
        val builder = Request.Builder().get().url(url).header("Accept", "application/json")
        when (provider) {
            "anthropic" -> builder.header("x-api-key", key).header("anthropic-version", "2023-06-01")
            "gemini" -> builder.header("x-goog-api-key", key)
            else -> if (key.isNotBlank()) builder.header("Authorization", "Bearer $key")
        }
        client.newBuilder().followRedirects(false).followSslRedirects(false).build()
            .newCall(builder.build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ApiHttpException.from(response.code, raw, response.header("Retry-After"))
                return JSONObject(raw)
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
                reasoning = params.any { it.contains("reasoning") } || model.optBoolean("reasoning", false),
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
