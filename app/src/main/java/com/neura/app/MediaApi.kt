package com.neura.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Media routes. The active protocol adapter determines which endpoints to call. */
object MediaRoute {
    const val CHAT = "chat"
    const val IMAGE = "image"
    const val SPEECH = "tts"
    const val TRANSCRIPTION = "stt"
    const val VIDEO = "video"
    const val UNSUPPORTED = "unsupported"
}

data class MediaResult(val text: String = "", val attachments: List<Attachment> = emptyList())

class MediaApi {
    private val http = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun execute(
        context: Context,
        protocol: String,
        endpoint: String,
        apiKey: String,
        model: String,
        route: String,
        text: String,
        attachments: List<Attachment>,
        voice: String,
        videoSeconds: Int,
        videoRatio: String,
        onProgress: suspend (String) -> Unit
    ): MediaResult = withContext(Dispatchers.IO) {
        val base = endpoint.trim().trimEnd('/')
        checkSecureEndpoint(base)
        require(ApiProtocols.canRun(protocol, route)) { "Протокол ${ApiProtocols.label(protocol)} не поддерживает этот тип медиа" }
        require(apiKey.isNotBlank()) { "Для медиа-запроса необходим API-ключ" }
        when (protocol) {
            ApiProtocols.GEMINI -> when (route) {
                MediaRoute.SPEECH -> geminiTextToSpeech(context, base, apiKey, model, text, voice)
                MediaRoute.TRANSCRIPTION -> geminiSpeechToText(context, base, apiKey, model, attachments)
                MediaRoute.VIDEO -> geminiVideo(context, base, apiKey, model, text, videoSeconds, videoRatio, onProgress)
                else -> error("Нет метода для этого типа медиа")
            }
            else -> when (route) {
                MediaRoute.SPEECH -> textToSpeech(context, base, apiKey, model, text, voice)
                MediaRoute.TRANSCRIPTION -> speechToText(context, base, apiKey, model, attachments)
                MediaRoute.VIDEO -> generateVideo(context, base, apiKey, model, text, videoSeconds, videoRatio, onProgress)
                else -> error("Нет совместимого API для выбранного режима")
            }
        }
    }

    private fun checked(request: Request): okhttp3.Response {
        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            val raw = response.body?.string().orEmpty()
            val status = response.code
            val retryAfter = response.header("Retry-After")
            response.close()
            throw ApiHttpException.from(status, raw, retryAfter)
        }
        return response
    }

    private fun postJson(url: String, key: String, json: JSONObject): JSONObject {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $key")
            .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return checked(request).use { response -> JSONObject(response.body?.string() ?: "{}") }
    }

    private fun textToSpeech(context: Context, base: String, key: String, model: String, text: String, voice: String): MediaResult {
        require(text.isNotBlank()) { "Для озвучивания введи текст" }
        val json = JSONObject().put("model", model).put("input", text)
            .put("voice", voice.ifBlank { "alloy" }).put("response_format", "mp3")
        val request = Request.Builder().url("$base/audio/speech").header("Authorization", "Bearer $key")
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        val attachment = checked(request).use { response ->
            val type = response.header("Content-Type").orEmpty()
            if (type.contains("json")) {
                throw IllegalStateException("Провайдер вернул JSON вместо MP3: ${response.body?.string().orEmpty().take(200)}")
            }
            saveMedia(context, response, "mp3", "audio/mpeg", 30 * 1024 * 1024)
        }
        return MediaResult("Озвучивание готово", listOf(attachment))
    }

    private fun speechToText(context: Context, base: String, key: String, model: String, attachments: List<Attachment>): MediaResult {
        val audio = attachments.firstOrNull {
            it.mimeType.startsWith("audio/") || it.name.endsWith(".mp3", true) || it.name.endsWith(".m4a", true)
        } ?: throw IllegalArgumentException("Для расшифровки прикрепи аудиофайл (MP3, WAV, M4A и др.)")
        val source = context.contentResolver.openInputStream(Uri.parse(audio.uri))
            ?: throw IllegalStateException("Не удалось открыть выбранный аудиофайл")
        val bytes = source.use { it.readBytesLimited(25 * 1024 * 1024) }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("file", audio.name, bytes.toRequestBody(audio.mimeType.toMediaType()))
            .build()
        val request = Request.Builder().url("$base/audio/transcriptions")
            .header("Authorization", "Bearer $key").post(body).build()
        val output = checked(request).use { response -> JSONObject(response.body?.string().orEmpty()) }
        val result = output.optString("text")
        require(result.isNotBlank()) { "Провайдер не вернул расшифровку" }
        return MediaResult(result)
    }

    private suspend fun generateVideo(
        context: Context, base: String, key: String, model: String, text: String,
        seconds: Int, ratio: String, onProgress: suspend (String) -> Unit
    ): MediaResult {
        require(text.isNotBlank()) { "Опиши видео, которое нужно создать" }
        val payload = JSONObject().put("model", model).put("prompt", text)
        // Different video models accept different parameters. We send only values explicitly chosen.
        if (seconds > 0) payload.put("duration", seconds)
        if (ratio in setOf("16:9", "9:16", "1:1")) payload.put("aspect_ratio", ratio)
        val created = postJson("$base/videos", key, payload)
        val id = created.optString("id").ifBlank { throw IllegalStateException("Видео API не вернул ID задания") }
        val initialStatus = created.optString("status", "pending")
        onProgress("Видео: $initialStatus · задание $id")
        // Never re-submit a paid generation while polling. A stopped request can be recovered by job ID.
        for (attempt in 0 until 45) {
            coroutineContext.ensureActive()
            val statusResponse = if (attempt == 0 && initialStatus == "completed") created else {
                delay(12_000)
                coroutineContext.ensureActive()
                val statusRequest = Request.Builder().url("$base/videos/${java.net.URLEncoder.encode(id, "UTF-8")}")
                    .header("Authorization", "Bearer $key").get().build()
                checked(statusRequest).use { JSONObject(it.body?.string().orEmpty()) }
            }
            val status = statusResponse.optString("status", "pending")
            when (status) {
                "completed" -> {
                    onProgress("Сохраняю готовое видео…")
                    val urls = statusResponse.optJSONArray("unsigned_urls")
                    val first = urls?.optString(0).orEmpty()
                    val mediaUrl = if (first.isBlank()) "$base/videos/${java.net.URLEncoder.encode(id, "UTF-8")}/content?index=0"
                        else if (first.startsWith("/")) {
                            val root = URI(base)
                            "${root.scheme}://${root.authority}$first"
                        } else first
                    val videoUri = URI(mediaUrl)
                    require(videoUri.scheme.equals("https", true) && videoUri.host != null && videoUri.rawUserInfo == null) {
                        "Сервер вернул недопустимый HTTPS-адрес видео (job $id)"
                    }
                    val requestBuilder = Request.Builder().url(mediaUrl).get()
                    // Never leak the API key to third-party CDN hosts.
                    if (videoUri.host.equals(URI(base).host, ignoreCase = true)) {
                        requestBuilder.header("Authorization", "Bearer $key")
                    }
                    val request = requestBuilder.build()
                    val video = checked(request).use { saveMedia(context, it, "mp4", "video/mp4", 300 * 1024 * 1024) }
                    return MediaResult("Видео готово · задание $id", listOf(video))
                }
                "failed", "cancelled", "expired" -> {
                    throw IllegalStateException("Генерация видео $status · задание $id: ${statusResponse.optString("error")}")
                }
                else -> onProgress("Генерирую видео · ${attempt + 1}/45 · $status · $id")
            }
        }
        throw IllegalStateException("Генерация продолжается. ID задания: $id. Не отправляй повторно тот же запрос, чтобы избежать повторного списания.")
    }

    // Gemini Native media adapters use Google's own request and response schemas.
    private fun geminiTextToSpeech(
        context: Context, base: String, key: String, model: String, text: String, voice: String
    ): MediaResult {
        require(text.isNotBlank()) { "Введи текст для озвучивания" }
        val safeModel = java.net.URLEncoder.encode(model, "UTF-8").replace("+", "%20")
        // Google TTS voice names differ from OpenAI voices such as alloy.
        val selectedVoice = if (voice.equals("alloy", true) || voice.isBlank()) "Kore" else voice
        val requestJson = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", selectedVoice)))))
        val request = Request.Builder().url("$base/models/$safeModel:generateContent")
            .header("x-goog-api-key", key)
            .post(requestJson.toString().toRequestBody("application/json".toMediaType())).build()
        val obj = checked(request).use { JSONObject(it.body?.string().orEmpty()) }
        val parts = obj.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val sound = (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optJSONObject("inlineData") }
            .firstOrNull { it.optString("mimeType").startsWith("audio/") }
            ?: throw IllegalStateException("Gemini не вернул аудио: проверь, является ли модель TTS")
        val bytes = Base64.decode(sound.optString("data"), Base64.DEFAULT)
        require(bytes.isNotEmpty() && bytes.size <= 30 * 1024 * 1024) { "Получено пустое или слишком большое аудио" }
        // Legacy generateContent TTS returns signed 16-bit PCM at 24 kHz.
        val wav = buildWav(bytes, 24_000)
        return MediaResult("Озвучивание готово", listOf(saveBytes(context, wav, "wav", "audio/wav")))
    }

    private fun geminiSpeechToText(
        context: Context, base: String, key: String, model: String, attachments: List<Attachment>
    ): MediaResult {
        val audio = attachments.firstOrNull { it.mimeType.startsWith("audio/") }
            ?: throw IllegalArgumentException("Прикрепи аудиофайл")
        val bytes = context.contentResolver.openInputStream(Uri.parse(audio.uri))?.use {
            it.readBytesLimited(17 * 1024 * 1024)
        } ?: throw IllegalStateException("Не удалось открыть аудиофайл")
        val mime = if (audio.mimeType == "audio/x-wav") "audio/wav" else audio.mimeType
        val prompt = JSONArray().put(JSONObject().put("text", "Точно расшифруй речь из этого аудио. Верни только текст."))
            .put(JSONObject().put("inlineData", JSONObject()
                .put("mimeType", mime)
                .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))))
        val json = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", prompt)))
        val safeModel = java.net.URLEncoder.encode(model, "UTF-8").replace("+", "%20")
        val request = Request.Builder().url("$base/models/$safeModel:generateContent")
            .header("x-goog-api-key", key)
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        val output = checked(request).use { JSONObject(it.body?.string().orEmpty()) }
        val parts = output.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val transcript = (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text") }.joinToString("\n").trim()
        require(transcript.isNotBlank()) { "Gemini не вернул расшифровку" }
        return MediaResult(transcript)
    }

    private suspend fun geminiVideo(
        context: Context, base: String, key: String, model: String, text: String,
        seconds: Int, ratio: String, onProgress: suspend (String) -> Unit
    ): MediaResult {
        require(text.isNotBlank()) { "Опиши видео" }
        require(model.startsWith("gemini-omni-")) {
            "Для Gemini Native в Neura используется Video Interactions API (модели Gemini Omni). Для Veo выбери OpenAI-совместимый endpoint Gemini, если он предоставляет Video API."
        }
        // New Gemini Omni Interactions API; initiate only once, poll operation separately.
        val input = JSONObject().put("model", model).put("input", text)
            .put("response_format", JSONObject().put("type", "video").put("delivery", "uri"))
        val created = geminiJson("$base/interactions", key, input)
        val id = created.optString("id")
        var response = created
        for (attempt in 0 until 45) {
            coroutineContext.ensureActive()
            val video = findGeminiVideo(response)
            if (video != null) {
                val (videoUrl, encoded) = video
                val attachment = if (encoded.isNotEmpty()) {
                    val bytes = Base64.decode(encoded, Base64.DEFAULT)
                    saveBytes(context, bytes, "mp4", "video/mp4")
                } else {
                    val uri = URI(videoUrl)
                    require(uri.scheme.equals("https", true) && uri.host != null && uri.rawUserInfo == null) { "Недопустимый адрес видео" }
                    val builder = Request.Builder().get().url(videoUrl)
                    if (uri.host.equals(URI(base).host, ignoreCase = true)) builder.header("x-goog-api-key", key)
                    checked(builder.build()).use { saveMedia(context, it, "mp4", "video/mp4", 300 * 1024 * 1024) }
                }
                return MediaResult("Видео готово", listOf(attachment))
            }
            val status = response.optString("status")
            if (status in setOf("failed", "cancelled", "expired")) throw IllegalStateException("Генерация видео завершилась: $status")
            require(id.isNotBlank()) { "Gemini не вернул ID операции и видеоданные" }
            onProgress("Видео обрабатывается · ${attempt + 1}/45 · $id")
            delay(12_000)
            coroutineContext.ensureActive()
            val req = Request.Builder().get().url("$base/interactions/${java.net.URLEncoder.encode(id, "UTF-8")}")
                .header("x-goog-api-key", key).build()
            response = checked(req).use { JSONObject(it.body?.string().orEmpty()) }
        }
        throw IllegalStateException("Видео всё ещё генерируется, ID: $id. Не запускай повторно платное задание.")
    }

    private fun findGeminiVideo(json: JSONObject): Pair<String, String>? {
        val direct = json.optJSONObject("output_video")
        if (direct != null) {
            val url = direct.optString("uri")
            val bytes = direct.optString("data")
            if (url.startsWith("https://") || bytes.isNotBlank()) return url to bytes
        }
        val steps = json.optJSONArray("steps") ?: JSONArray()
        for (i in 0 until steps.length()) {
            val content = steps.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val item = content.optJSONObject(j) ?: continue
                if (item.optString("type") == "video") {
                    val uri = item.optString("uri")
                    val data = item.optString("data")
                    if (uri.startsWith("https://") || data.isNotBlank()) return uri to data
                }
            }
        }
        return null
    }

    private fun geminiJson(url: String, key: String, json: JSONObject): JSONObject {
        val req = Request.Builder().url(url).header("x-goog-api-key", key)
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        return checked(req).use { JSONObject(it.body?.string().orEmpty()) }
    }

    private fun buildWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1.toShort()).putShort(1.toShort())
        header.putInt(sampleRate).putInt(sampleRate * 2).putShort(2.toShort()).putShort(16.toShort())
        header.put("data".toByteArray()).putInt(pcm.size)
        return header.array() + pcm
    }

    private fun saveBytes(context: Context, bytes: ByteArray, extension: String, mime: String): Attachment {
        require(bytes.isNotEmpty() && bytes.size <= 300 * 1024 * 1024) { "Недопустимый размер медиафайла" }
        val folder = File(context.filesDir, "generated_media").apply { mkdirs() }
        val file = File(folder, "neura-${UUID.randomUUID()}.$extension")
        file.writeBytes(bytes)
        return Attachment(name = file.name, uri = Uri.fromFile(file).toString(), mimeType = mime, size = bytes.size.toLong())
    }

    private fun saveMedia(context: Context, response: okhttp3.Response, extension: String, mime: String, maxBytes: Int): Attachment {
        val mediaDir = File(context.filesDir, "generated_media").apply { mkdirs() }
        val file = File(mediaDir, "neura-${UUID.randomUUID()}.$extension")
        try {
            val body = response.body ?: throw IllegalStateException("Пустой медиаответ")
            body.byteStream().use { input -> file.outputStream().use { output ->
                val buf = ByteArray(16 * 1024)
                var written = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    written += n
                    if (written > maxBytes) throw IllegalStateException("Файл превышает лимит ${maxBytes / 1024 / 1024} МБ")
                    output.write(buf, 0, n)
                }
            } }
            require(file.length() > 0) { "Провайдер вернул пустой файл" }
            return Attachment(name = file.name, uri = Uri.fromFile(file).toString(), mimeType = mime, size = file.length())
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val n = read(chunk)
            if (n == -1) break
            if (buffer.size() + n > limit) throw IllegalArgumentException("Аудиофайл больше 25 МБ")
            buffer.write(chunk, 0, n)
        }
        return buffer.toByteArray()
    }
}
