package com.neura.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val graphite = Color(0xFF0D1011)
private val steel = Color(0xFF171B1D)
private val gold = Color(0xFFD9BF93)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(13, 16, 17)
        window.navigationBarColor = android.graphics.Color.rgb(13, 16, 17)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { NeuraApp() }
    }
}

@Composable
fun NeuraApp() {
    val context = LocalContext.current
    val store = remember { LocalStore(context.applicationContext) }
    val api = remember { UniversalApi() }
    val scope = rememberCoroutineScope()
    var chats by remember { mutableStateOf(store.loadChats()) }
    var currentId by remember { mutableStateOf(chats.firstOrNull()?.id ?: "") }
    var page by remember { mutableStateOf("chat") }
    var provider by remember { mutableStateOf(store.string("provider", "openrouter")) }
    var endpoint by remember { mutableStateOf(store.string("endpoint_$provider", Providers.endpoint(provider))) }
    var apiKey by remember { mutableStateOf(store.loadKey(provider)) }
    var model by remember { mutableStateOf(store.string("model_$provider", Providers.defaultModel(provider))) }
    var imageMode by remember { mutableStateOf(store.bool("image_mode_$provider", false)) }
    var imageApi by remember { mutableStateOf(store.bool("image_api_$provider", false)) }
    var web by remember { mutableStateOf(store.bool("web_$provider", false)) }
    var reasoning by remember { mutableStateOf(store.bool("reasoning_$provider", false)) }
    var systemPrompt by remember { mutableStateOf(store.string("system", "Ты внимательный AI-ассистент. Отвечай на языке пользователя.") ) }
    var maxTokens by remember { mutableStateOf(store.string("tokens", "4096")) }
    var temperature by remember { mutableStateOf(store.string("temperature", "1")) }
    var dark by remember { mutableStateOf(store.bool("dark", true)) }
    var input by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var streaming by remember { mutableStateOf("") }
    var streamingImages by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val items = uris.mapNotNull { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { buildAttachmentFromUri(context, uri) }.getOrNull()
        }
        pending = (pending + items).distinctBy { it.uri }
    }

    fun persist(next: List<Conversation>) {
        chats = next.sortedByDescending { it.updatedAt }
        store.saveChats(chats)
    }

    fun updateChat(id: String, edit: (Conversation) -> Conversation) {
        persist(chats.map { if (it.id == id) edit(it).copy(updatedAt = System.currentTimeMillis()) else it })
    }

    fun stop() { activeJob?.cancel() }

    fun openChat(chat: Conversation) {
        stop(); busy = false; streaming = ""; streamingImages = emptyList()
        currentId = chat.id; error = ""; page = "chat"; pending = emptyList()
    }

    fun createChat() {
        stop(); busy = false; streaming = ""; streamingImages = emptyList()
        val chat = Conversation()
        persist(listOf(chat) + chats)
        currentId = chat.id; input = ""; pending = emptyList(); error = ""; page = "chat"
    }

    fun switchProvider(next: String) {
        stop()
        provider = next
        store.set("provider", next)
        endpoint = store.string("endpoint_$next", Providers.endpoint(next))
        apiKey = store.loadKey(next)
        model = store.string("model_$next", Providers.defaultModel(next))
        web = store.bool("web_$next")
        reasoning = store.bool("reasoning_$next")
        imageMode = store.bool("image_mode_$next")
        imageApi = store.bool("image_api_$next")
    }

    fun selectModel(item: CatalogModel) {
        model = item.id
        imageMode = item.outputImage
        imageApi = item.imageApi
        store.set("model_$provider", model)
        store.set("image_mode_$provider", imageMode)
        store.set("image_api_$provider", imageApi)
        page = "chat"
    }

    fun send(text: String, regeneration: Boolean = false) {
        if (busy) return
        if (apiKey.isBlank() && provider != "custom") { error = "В настройках укажи API-ключ"; return }
        if (model.isBlank()) { error = "Выбери модель"; return }
        if (!regeneration && text.isBlank() && pending.isEmpty()) return
        var id = currentId
        if (chats.none { it.id == id }) {
            val newChat = Conversation()
            persist(listOf(newChat) + chats)
            id = newChat.id; currentId = id
        }
        val before = chats.first { it.id == id }
        val history = if (regeneration) before.messages else before.messages + Message(role = "user", text = text.trim(), attachments = pending)
        if (!regeneration) {
            input = ""; pending = emptyList()
            updateChat(id) { c ->
                c.copy(title = if (c.messages.isEmpty()) text.trim().ifBlank { "Работа с файлами" }.take(54) else c.title, messages = history)
            }
        }
        error = ""; streaming = ""; streamingImages = emptyList(); busy = true
        val targetId = id
        activeJob = scope.launch {
            fun saveResult() {
                if (streaming.isNotBlank() || streamingImages.isNotEmpty()) {
                    val message = Message(role = "assistant", text = streaming, attachments = streamingImages)
                    updateChat(targetId) { it.copy(messages = it.messages + message) }
                }
            }
            try {
                api.stream(
                    context.applicationContext, provider, endpoint, apiKey.trim(), model.trim(),
                    systemPrompt, web && ApiCapabilities.web(provider, model),
                    reasoning && ApiCapabilities.reasoning(provider, model),
                    maxTokens.toIntOrNull()?.coerceIn(1, 32000) ?: 4096,
                    temperature.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 1.0,
                    history, imageMode, imageApi,
                    onText = { chunk -> withContext(Dispatchers.Main) { streaming += chunk } },
                    onImage = { image -> withContext(Dispatchers.Main) { streamingImages = streamingImages + image } }
                )
                saveResult()
                if (streaming.isBlank() && streamingImages.isEmpty()) error = "Модель вернула пустой ответ. Проверь её возможности в каталоге."
            } catch (_: CancellationException) {
                saveResult()
            } catch (e: Exception) {
                saveResult()
                error = e.message ?: "Не удалось получить ответ"
            } finally {
                streaming = ""; streamingImages = emptyList(); busy = false; activeJob = null
            }
        }
    }

    fun exportChat(chat: Conversation) {
        val content = buildString {
            append("# ").append(chat.title).append("\n\n")
            chat.messages.forEach { message ->
                append(if (message.role == "user") "## Вы" else "## Neura").append("\n\n")
                append(message.text).append("\n")
                message.attachments.forEach { append("- ").append(it.name).append(" (файл)\n") }
                append("\n")
            }
        }
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content)
        }, "Экспорт чата"))
    }

    val colorScheme = if (dark) darkColorScheme(
        primary = gold, onPrimary = Color(0xFF282013), background = graphite,
        onBackground = Color(0xFFF0EEE9), surface = steel, onSurface = Color(0xFFF0EEE9),
        surfaceVariant = Color(0xFF272C2D), onSurfaceVariant = Color(0xFFA5ADAC),
        primaryContainer = Color(0xFF353127), onPrimaryContainer = Color(0xFFE8D7B8),
        outlineVariant = Color(0xFF343B3B), secondary = Color(0xFFC4D2CB), error = Color(0xFFF0A5A0)
    ) else lightColorScheme(
        primary = Color(0xFF82613A), onPrimary = Color.White, background = Color(0xFFFAF9F6),
        onBackground = Color(0xFF191B1C), surface = Color.White, surfaceVariant = Color(0xFFF0EEEA),
        onSurfaceVariant = Color(0xFF646867), outlineVariant = Color(0xFFE3DED4),
        primaryContainer = Color(0xFFEFE5D3), onPrimaryContainer = Color(0xFF3D2E21)
    )

    BackHandler(page != "chat") { page = "chat" }
    MaterialTheme(colorScheme = colorScheme) {
        Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding(), color = MaterialTheme.colorScheme.background) {
            when (page) {
                "settings" -> SettingsScreen(
                    provider, apiKey, endpoint, model, imageMode, web, reasoning,
                    systemPrompt, maxTokens, temperature, dark,
                    onBack = { page = "chat" },
                    onProvider = ::switchProvider,
                    onKey = { apiKey = it },
                    onSaveKey = { store.saveKey(apiKey.trim(), provider); Toast.makeText(context, "Ключ сохранён", Toast.LENGTH_SHORT).show() },
                    onEndpoint = { endpoint = it; store.set("endpoint_$provider", it) },
                    onModelLibrary = { page = "models" },
                    onImageMode = { imageMode = it; imageApi = it; store.set("image_mode_$provider", it); store.set("image_api_$provider", it) },
                    onWeb = { web = it; store.set("web_$provider", it) },
                    onReasoning = { reasoning = it; store.set("reasoning_$provider", it) },
                    onPrompt = { systemPrompt = it; store.set("system", it) },
                    onTokens = { maxTokens = it; store.set("tokens", it) },
                    onTemperature = { temperature = it; store.set("temperature", it) },
                    onTheme = { dark = it; store.set("dark", it) }
                )
                "models" -> ModelLibraryScreen(provider, endpoint, apiKey, model, store,
                    onBack = { page = "chat" }, onSelect = ::selectModel,
                    onManual = { id, image ->
                        model = id; imageMode = image; imageApi = image
                        store.set("model_$provider", model)
                        store.set("image_mode_$provider", image)
                        store.set("image_api_$provider", image)
                        page = "chat"
                    }
                )
                "history" -> HistoryScreen(chats, onBack = { page = "chat" }, onOpen = ::openChat,
                    onNew = ::createChat, onRename = { renameId = it.id; renameValue = it.title },
                    onDelete = { deleteId = it.id }, onShare = ::exportChat)
                else -> ChatScreen(
                    chat = chats.find { it.id == currentId }, provider = provider, model = model,
                    imageMode = imageMode, web = web, reasoning = reasoning,
                    input = input, onInput = { input = it }, pending = pending,
                    onPickFile = { filePicker.launch(arrayOf("*/*")) },
                    onRemoveFile = { removed -> pending = pending.filterNot { it.id == removed.id } },
                    busy = busy, streaming = streaming, streamingImages = streamingImages,
                    error = error, onClearError = { error = "" },
                    onRetry = { send("", regeneration = true) },
                    onOpenModels = { page = "models" },
                    onOpenSettings = { page = "settings" },
                    onOpenHistory = { page = "history" },
                    onNew = ::createChat,
                    onWeb = { web = it; store.set("web_$provider", it) },
                    onReasoning = { reasoning = it; store.set("reasoning_$provider", it) },
                    onSend = { send(it) }, onStop = ::stop,
                    onEdit = { item -> editingId = item.id; editingText = item.text },
                    onRegenerate = {
                        val c = chats.find { it.id == currentId }
                        if (c?.messages?.lastOrNull()?.role == "assistant") {
                            updateChat(currentId) { it.copy(messages = it.messages.dropLast(1)) }
                            send("", regeneration = true)
                        }
                    }
                )
            }
        }
        if (renameId != null) AlertDialog(onDismissRequest = { renameId = null }, title = { Text("Название чата") },
            text = { OutlinedTextField(renameValue, { renameValue = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { val id = renameId!!; updateChat(id) { it.copy(title = renameValue.trim().ifBlank { "Чат" }) }; renameId = null }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { renameId = null }) { Text("Отмена") } })
        if (deleteId != null) AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("Удалить диалог?") },
            text = { Text("Историю этого диалога восстановить нельзя.") },
            confirmButton = { TextButton(onClick = {
                val id = deleteId!!
                if (id == currentId) stop()
                persist(chats.filterNot { it.id == id })
                if (currentId == id) currentId = chats.firstOrNull()?.id ?: ""
                deleteId = null
            }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Отмена") } })
        if (editingId != null) AlertDialog(onDismissRequest = { editingId = null }, title = { Text("Изменить сообщение") },
            text = { OutlinedTextField(editingText, { editingText = it }, minLines = 3, maxLines = 8) },
            confirmButton = { TextButton(onClick = {
                val id = editingId!!
                val current = chats.find { it.id == currentId }
                val index = current?.messages?.indexOfFirst { it.id == id } ?: -1
                if (index >= 0 && editingText.isNotBlank()) {
                    updateChat(currentId) { it.copy(messages = it.messages.take(index) + it.messages[index].copy(text = editingText.trim())) }
                    editingId = null
                    send("", regeneration = true)
                }
            }) { Text("Отправить заново") } },
            dismissButton = { TextButton(onClick = { editingId = null }) { Text("Отмена") } })
    }
}
