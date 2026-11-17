package com.neura.app

import org.json.JSONObject

class ApiHttpException(
    val status: Int,
    val retryAfter: String?,
    val limitSource: String?,
    private val remoteMessage: String
) : Exception(buildMessage(status, retryAfter, limitSource, remoteMessage)) {
    companion object {
        fun from(status: Int, body: String, retryAfter: String?): ApiHttpException {
            val obj = runCatching { JSONObject(body) }.getOrNull()
            val err = obj?.optJSONObject("error")
            val metadata = err?.optJSONObject("metadata")
            val source = metadata?.optString("limit_source")?.ifBlank { null }
            val message = err?.optString("message")?.ifBlank { null } ?: body.take(250)
            return ApiHttpException(status, retryAfter, source, message)
        }

        private fun buildMessage(code: Int, retry: String?, source: String?, message: String): String {
            if (code == 429) {
                val explanation = when (source) {
                    "openrouter_free_tier_per_minute" -> "Превышен лимит бесплатных запросов OpenRouter."
                    "upstream_provider_shared_pool" -> "Провайдер модели сейчас перегружен."
                    else -> "Модель или провайдер временно ограничивает запросы."
                }
                val wait = retry?.let { " Повторить после: $it." }.orEmpty()
                return "$explanation$wait Попробуй позже или выбери другую модель."
            }
            return when (code) {
                401 -> "Неверный или недействительный API-ключ (401)."
                402 -> "Недостаточно средств или достигнут лимит оплаты API (402)."
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
