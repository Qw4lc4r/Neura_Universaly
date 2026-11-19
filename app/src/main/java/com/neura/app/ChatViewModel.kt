package com.neura.app

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import java.util.UUID
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GenerationSettings(
    val protocol: String, val endpoint: String, val key: String, val model: String,
    val systemPrompt: String, val web: Boolean, val reasoning: Boolean,
    val maxTokens: Int, val temperature: Double, val imageMode: Boolean, val imageApi: Boolean,
    val route: String, val voice: String, val videoSeconds: Int, val videoRatio: String, val routerProfile: String = "auto",
    val readLinks: Boolean = false, val searchKey: String = "", val routerReasoning: RouterReasoning? = null,
    val reasoningFormat: ReasoningFormat = ReasoningFormat.AUTO
)

/** Owns each request and its partial output across navigation and configuration changes. */
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    val store = LocalStore(application)
    val chats = mutableStateOf(store.loadChats())
    val currentId = mutableStateOf(chats.value.firstOrNull()?.id.orEmpty())
    val input = mutableStateOf("")
    val pending = mutableStateOf<List<Attachment>>(emptyList())
    val busy = mutableStateOf(false)
    val reasoningRequested = mutableStateOf(false)
    val streamingReasoning = mutableStateOf("")
    val liveMessageId = mutableStateOf("")
    val liveModel = mutableStateOf("")
    val sendSequence = mutableLongStateOf(0L)
    val streaming = mutableStateOf("")
    val streamingImages = mutableStateOf<List<Attachment>>(emptyList())
    val error = mutableStateOf(store.historyError)
    private val api = UniversalApi()
    private val webContext = WebContext()
    private val catalog = ModelCatalog(application)
    private val modelCache = LinkedHashMap<String, CatalogResult>()

    fun catalogId(provider: String, protocol: String, endpoint: String, key: String): String =
        runCatching { "$provider|${ApiConnection.identity(protocol, endpoint, key)}" }.getOrDefault("$provider|invalid")

    fun cachedCatalog(id: String): CatalogResult? = modelCache[id]

    suspend fun loadCatalog(provider: String, protocol: String, endpoint: String, key: String, refresh: Boolean): CatalogResult {
        val id = catalogId(provider, protocol, endpoint, key)
        if (!refresh) modelCache[id]?.let { return it }
        return catalog.list(provider, endpoint, key, protocol).also {
            modelCache[id] = it
            if (modelCache.size > 12) modelCache.remove(modelCache.keys.first())
        }
    }

    private val mediaApi = MediaApi()
    private val saves = Channel<List<Conversation>>(Channel.CONFLATED)
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var generation = 0L

    init {
        persistenceScope.launch {
            for (snapshot in saves) {
                try { withContext(Dispatchers.IO) { store.saveChats(snapshot) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error.value = "Не удалось сохранить историю: ${failure.message}" }
            }
        }.invokeOnCompletion { persistenceScope.cancel() }
    }

    override fun onCleared() {
        // Drain queued writes, including a cancelled request's partial answer, on normal app exit.
        val active = job
        if (active == null) saves.close() else {
            active.invokeOnCompletion { saves.close() }
            active.cancel()
        }
        super.onCleared()
    }

    fun persist(next: List<Conversation>) {
        chats.value = next.sortedByDescending { it.updatedAt }
        saves.trySend(chats.value)
    }

    fun updateChat(id: String, edit: (Conversation) -> Conversation) {
        persist(chats.value.map { if (it.id == id) edit(it).copy(updatedAt = System.currentTimeMillis()) else it })
    }

    fun stop() { job?.cancel() }

    private fun leaveChat() {
        stop()
        generation++
        busy.value = false
        streamingReasoning.value = ""
        streaming.value = ""
        streamingImages.value = emptyList()
        error.value = ""
        input.value = ""
        pending.value = emptyList()
    }

    fun openChat(chat: Conversation) { leaveChat(); currentId.value = chat.id }
    fun createChat() {
        val current = chats.value.firstOrNull { it.id == currentId.value }
        if (!busy.value && current?.messages.isNullOrEmpty()) return
        val empty = chats.value.firstOrNull { it.messages.isEmpty() }
        if (empty != null) { openChat(empty); return }
        leaveChat()
        // A draft is persisted only when the user actually sends something.
        currentId.value = ""
    }

    fun send(text: String, settings: GenerationSettings, regeneration: Boolean = false, replacement: List<Message>? = null) {
        if (busy.value) return
        if (!regeneration && text.isBlank() && pending.value.isEmpty()) return
        val existing = chats.value.firstOrNull { it.id == currentId.value }
        val chat = existing ?: Conversation()
        val targetId = chat.id
        val history = if (regeneration) replacement ?: chat.messages else chat.messages +
            Message(role = "user", text = text.trim(), attachments = pending.value)
        if (history.lastOrNull()?.role != "user") {
            error.value = "Нет сообщения для повторной отправки"
            return
        }
        if (!regeneration) {
            if (existing == null) {
                persist(listOf(chat.copy(title = text.trim().ifBlank { "Работа с файлами" }.take(54),
                    messages = history)) + chats.value)
                currentId.value = targetId
            } else updateChat(targetId) { it.copy(
                title = if (it.messages.isEmpty()) text.trim().ifBlank { "Работа с файлами" }.take(54) else it.title,
                messages = history
            ) }
            input.value = ""
            pending.value = emptyList()
        }
        val requestId = ++generation
        val answerId = UUID.randomUUID().toString()
        liveMessageId.value = answerId
        liveModel.value = settings.model
        sendSequence.longValue++
        reasoningRequested.value = settings.reasoning
        streamingReasoning.value = ""
        busy.value = true; error.value = ""; streaming.value = ""; streamingImages.value = emptyList()
        job = viewModelScope.launch {
            // These buffers belong to this request, never to whichever chat is visible now.
            var output = ""
            var thoughts = ""
            var status = "complete"
            var attachments = emptyList<Attachment>()
            fun publish() {
                if (generation == requestId) {
                    streamingReasoning.value = thoughts
                    streaming.value = output
                    streamingImages.value = attachments
                }
            }
            try {
                with(settings) {
                    if (route in setOf(MediaRoute.SPEECH, MediaRoute.TRANSCRIPTION, MediaRoute.VIDEO)) {
                        val last = history.last()
                        val result = mediaApi.execute(getApplication(), protocol, endpoint, key, model,
                            route, last.text, last.attachments, voice, videoSeconds, videoRatio,
                            onProgress = { detail -> withContext(Dispatchers.Main) { output = detail; publish() } })
                        output = result.text; attachments = result.attachments
                    } else {
                        val portableSearch = web && searchKey.isNotBlank() && route == MediaRoute.CHAT
                        val nativeSearch = web && !portableSearch && route == MediaRoute.CHAT
                        val needsSources = portableSearch || readLinks
                        val context = if (needsSources) {
                            streaming.value = "Загружаю веб-источники…"
                            webContext.collect(history.last().text, portableSearch, readLinks, searchKey)
                        } else ""
                        if (needsSources) streaming.value = ""
                        val requestHistory = if (context.isBlank()) history else history.dropLast(1) +
                            history.last().copy(text = history.last().text + "\n\n" + context)
                        val stream = ThoughtStream(
                            onText = { chunk -> output += chunk; publish() },
                            onReasoning = { chunk -> if (reasoning) { thoughts += chunk; publish() } }
                        )
                        api.stream(getApplication(), protocol, endpoint, key, model, systemPrompt, nativeSearch, reasoning,
                            maxTokens, temperature, requestHistory, imageMode, imageApi,
                            onText = { chunk -> withContext(Dispatchers.Main) { stream.accept(chunk) } },
                            onImage = { image -> withContext(Dispatchers.Main) { attachments = attachments + image; publish() } },
                            onReasoning = { chunk -> withContext(Dispatchers.Main) {
                                if (reasoning) { thoughts += chunk; publish() }
                            } }, routerProfile = routerProfile, routerReasoning = routerReasoning, reasoningFormat = reasoningFormat)
                        stream.finish()
                    }
                }
                if (output.isBlank() && attachments.isEmpty()) {
                    status = "failed"
                    if (generation == requestId) error.value = "Модель вернула пустой ответ. Проверь возможности модели."
                }
            } catch (_: CancellationException) {
                status = "stopped"
                // Keep partial output in its original conversation.
            } catch (failure: Exception) {
                status = "failed"
                if (generation == requestId) error.value = failure.message ?: "Не удалось получить ответ"
            } finally {
                val hasOutput = output.isNotBlank() || thoughts.isNotBlank() || attachments.isNotEmpty()
                if (hasOutput && (replacement == null || status == "complete")) {
                    val message = Message(id = answerId, role = "assistant", text = output, attachments = attachments, model = settings.model, status = status, reasoning = thoughts, reasoningRequested = settings.reasoning)
                    updateChat(targetId) {
                        when {
                            replacement != null && it.messages == chat.messages -> it.copy(messages = history + message)
                            replacement == null && it.messages.take(history.size).map { m -> m.id } == history.map { m -> m.id } ->
                                it.copy(messages = history + message + it.messages.drop(history.size))
                            else -> it
                        }
                    }
                }
                if (generation == requestId) {
                    streamingReasoning.value = ""; streaming.value = ""; streamingImages.value = emptyList(); busy.value = false; job = null
                }
            }
        }
    }
}
