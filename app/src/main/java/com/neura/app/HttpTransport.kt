package com.neura.app

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

private fun Response.apiCredential(): String = request.header("Authorization")?.removePrefix("Bearer ")
        ?: request.header("x-api-key") ?: request.header("x-goog-api-key").orEmpty()

private fun Response.apiRequestId(): String? = header("X-Request-ID") ?: header("Request-ID")

internal fun Response.apiFailure(): ApiHttpException =
    ApiHttpException.from(code, peekBody(16 * 1024L).string(), header("Retry-After"), apiRequestId(), apiCredential())

internal fun Response.paymentFailure(json: JSONObject): ApiHttpException? =
    ApiHttpException.paymentFromEvent(json, apiCredential(), apiRequestId())

/** Keeps cancellation attached until the response body is fully consumed. */
internal suspend fun <T> Call.consume(block: suspend (Response) -> T): T = coroutineScope {
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { this@consume.cancel() }
    }
    try {
        execute().use { response -> block(response) }
    } catch (error: Exception) {
        coroutineContext.ensureActive()
        throw error
    } finally {
        watcher.cancel()
    }
}

/** SSE dispatches on blank lines and joins multiple data fields with a newline. */
internal class SseReader(private val source: BufferedSource) {
    fun next(): String? {
        val data = mutableListOf<String>()
        while (true) {
            val line = source.readUtf8Line()
            if (line == null || line.isEmpty()) {
                if (data.isNotEmpty()) return data.joinToString("\n")
                if (line == null) return null
            } else if (line == "data" || line.startsWith("data:")) {
                data += line.removePrefix("data").removePrefix(":").removePrefix(" ")
            }
        }
    }
}
