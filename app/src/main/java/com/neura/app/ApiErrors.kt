package com.neura.app

import org.json.JSONObject

class ApiHttpException(
    val status: Int,
    val retryAfter: String?,
    val limitSource: String?,
    remoteMessage: String,
    internal val affordableTokens: Int? = null,
    requestId: String? = null
) : Exception(buildMessage(status, retryAfter, limitSource, remoteMessage, affordableTokens, requestId)) {
    companion object {
        private val tokenLimit = Regex("\\b(?:max_tokens|max_completion_tokens|max_output_tokens)\\b", RegexOption.IGNORE_CASE)
        private val affordableLimit = Regex("\\bcan only afford\\s+(\\d+)\\b(?![.,]\\d)", RegexOption.IGNORE_CASE)
        private val requestIdPattern = Regex("[A-Za-z0-9._:-]{1,128}")

        fun from(status: Int, body: String, retryAfter: String?, requestId: String? = null, key: String = ""): ApiHttpException {
            val json = runCatching { JSONObject(body) }.getOrNull()
            val plainText = body.trimStart().takeUnless { it.startsWith('<') || it.startsWith('{') || it.startsWith('[') }.orEmpty()
            return parse(status, json, plainText, retryAfter, requestId, key)
        }

        internal fun paymentFromEvent(json: JSONObject, key: String, requestId: String?): ApiHttpException? {
            val envelope = json.optJSONObject("response")?.takeIf { it.has("error") } ?: json
            if (envelope.isNull("error")) return null
            val error = envelope.optJSONObject("error")
            if (error?.optInt("code") != 402 && error?.optInt("status") != 402 && envelope.optInt("code") != 402) return null
            return parse(402, envelope, "", null, requestId, key)
        }

        private fun parse(status: Int, obj: JSONObject?, plainText: String, retryAfter: String?, requestId: String?, key: String): ApiHttpException {
            val err = obj?.optJSONObject("error") ?: obj
            val metadata = err?.optJSONObject("metadata")
            val source = metadata.text("limit_source")
            val routerMessage = err.text("message") ?: obj.text("error") ?: obj.text("message") ?: obj.text("detail") ?: plainText
            val affordable = if (status == 402 && source != "openrouter_in_flight_budget" && tokenLimit.containsMatchIn(routerMessage)) {
                affordableLimit.find(routerMessage)?.groupValues?.get(1)?.toIntOrNull()
            } else null
            val upstream = if (status == 402) metadata.text("raw")?.let { raw ->
                if (raw.startsWith('{')) {
                    val nested = runCatching { JSONObject(raw) }.getOrNull()
                    nested?.optJSONObject("error").text("message") ?: nested.text("message") ?: nested.text("error")
                } else raw.takeUnless { it.startsWith('<') || it.startsWith('[') }
            } else null
            val message = if (upstream.isNullOrBlank() || upstream == routerMessage) routerMessage else {
                val provider = metadata.text("provider_name") ?: "Провайдер модели"
                "${routerMessage.take(150)}\n$provider · $upstream".trimStart()
            }
            fun redact(text: String): String = if (key.isBlank()) text else text.replace(key, "[ключ скрыт]")
            return ApiHttpException(status, retryAfter?.let(::redact), source, redact(message).take(420), affordable,
                (requestId ?: obj.text("request_id"))?.takeIf(requestIdPattern::matches)?.let(::redact))
        }

        private fun JSONObject?.text(name: String): String? = (this?.opt(name) as? String)?.trim()?.takeIf { it.isNotEmpty() }

        private fun buildMessage(code: Int, retry: String?, source: String?, message: String, affordable: Int?, requestId: String?): String {
            if (code == 429) {
                val explanation = when (source) {
                    "openrouter_free_tier_per_minute" -> "Превышен лимит бесплатных запросов OpenRouter."
                    "upstream_provider_shared_pool" -> "Провайдер модели сейчас перегружен."
                    else -> "Модель или провайдер временно ограничивает запросы."
                }
                val wait = retry?.let { " Повторить после: $it." }.orEmpty()
                return "$explanation$wait Попробуй позже или выбери другую модель."
            }
            if (code == 402) {
                val explanation = when {
                    affordable != null -> "Роутер отклонил запрос по стоимости. Доступный максимум ответа — $affordable токенов. Уменьши максимум в настройках модели."
                    source == "openrouter_in_flight_budget" -> "OpenRouter временно зарезервировал бюджет для других запросов. Повтори позже."
                    source == "openrouter_key_limit" -> "OpenRouter указал на лимит расходов этого API-ключа."
                    source == "openrouter_credits" -> "OpenRouter сообщил, что доступных кредитов для этого запроса недостаточно."
                    else -> "Роутер отклонил запрос с кодом оплаты."
                }
                val wait = if (source == "openrouter_in_flight_budget") retry?.toLongOrNull()
                    ?.takeIf { it >= 0 }?.let { " Повторить через $it сек." }.orEmpty() else ""
                return listOfNotNull("HTTP 402 · $explanation$wait", message.takeIf { it.isNotBlank() },
                    requestId?.let { "ID запроса $it" }).joinToString("\n")
            }
            return when (code) {
                401 -> "Неверный или недействительный API-ключ (401)."
                403 -> "Ключ не имеет доступа к выбранной модели (403)."
                404 -> "Модель или endpoint не найдены (404)."
                413 -> "Слишком большой файл или запрос (413)."
                422 -> "Модель отклонила параметры запроса (422): ${message.take(200)}"
                in 500..599 -> "Сервис модели временно недоступен ($code). Попробуй позже."
                else -> "Ошибка API $code: ${message.take(260)}"
            }
        }
    }
}
