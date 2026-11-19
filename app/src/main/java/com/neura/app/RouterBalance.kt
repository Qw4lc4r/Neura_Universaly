package com.neura.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object RouterBalance {
    private val client = OkHttpClient.Builder().followRedirects(false)
        .callTimeout(12, TimeUnit.SECONDS).build()

    suspend fun read(endpoint: String, key: String, protocol: String, path: String, field: String, unit: String): String =
        withContext(Dispatchers.IO) {
            val base = ApiConnection.baseUrl(endpoint).toHttpUrl()
            val isOpenRouter = base.host == "openrouter.ai" && base.port == 443 && base.encodedPath == "/api/v1"
            val effectivePath = path.trim().ifBlank { if (isOpenRouter) "/api/v1/key" else "" }
            val effectiveField = field.trim().ifBlank { if (isOpenRouter && path.isBlank()) "data.limit_remaining" else "" }
            require(effectivePath.startsWith("/") && !effectivePath.startsWith("//") && '\\' !in effectivePath &&
                '?' !in effectivePath && '#' !in effectivePath) { "Укажи путь к балансу, например /v1/balance." }
            val url = base.resolve(effectivePath) ?: error("Неверный путь к балансу")
            require(url.scheme == base.scheme && url.host == base.host && url.port == base.port) {
                "Запрос баланса должен идти на тот же сервер, что и API."
            }
            require(effectiveField.isNotBlank()) { "Укажи JSON-поле с остатком." }
            val request = Request.Builder().url(url).header("Accept", "application/json")
            if (key.isNotBlank()) when {
                protocol == ApiProtocols.GEMINI -> request.header("x-goog-api-key", key)
                protocol == ApiProtocols.ANTHROPIC && base.host != "api.onekey-ai.lol" ->
                    request.header("x-api-key", key).header("anthropic-version", "2023-06-01")
                else -> request.header("Authorization", "Bearer $key")
            }
            client.newCall(request.build()).consume { response ->
                require(response.isSuccessful) { "Сервер баланса вернул HTTP ${response.code}." }
                val source = response.body?.source() ?: error("Сервер вернул пустой баланс")
                require(!source.request(256 * 1024 + 1L)) { "Ответ баланса слишком большой" }
                val json = JSONObject(source.readUtf8())
                if (isOpenRouter && path.isBlank() && json.optJSONObject("data")?.opt("limit_remaining") === JSONObject.NULL) {
                    return@consume "Для ключа не установлен лимит расходов"
                }
                val value = remaining(json, effectiveField)
                val denomination = unit.trim().ifBlank { if (isOpenRouter && path.isBlank()) "USD" else "" }
                val display = listOf(value, denomination).filter { it.isNotBlank() }.joinToString(" ")
                if (isOpenRouter && path.isBlank()) "Лимит ключа · $display" else display
            }
        }

    internal fun remaining(json: JSONObject, field: String): String {
        val value = field.split('.').fold(json as Any?) { current, segment ->
            (current as? JSONObject)?.opt(segment)
        }
        require(value is Number || value is String) { "В ответе нет числового поля $field." }
        val decimal = value.toString().toBigDecimalOrNull() ?: error("Поле $field не содержит число.")
        require(decimal.precision() <= 40 && decimal.scale() in -20..40) { "Число баланса выходит за пределы формата." }
        return decimal.stripTrailingZeros().toPlainString()
    }
}
