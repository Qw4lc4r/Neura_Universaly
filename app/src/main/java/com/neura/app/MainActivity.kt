package com.neura.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.isSystemInDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NeuraApp() }
    }
}

@Composable
fun NeuraApp() {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val chatState: ChatViewModel = viewModel()
    val store = chatState.store
    val screens = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val historyDrawer = rememberDrawerState(DrawerValue.Closed)
    LaunchedEffect(historyDrawer.currentValue) {
        if (historyDrawer.isClosed) focusManager.clearFocus()
    }
    var chats by chatState.chats
    var currentId by chatState.currentId
    var page by rememberSaveable { mutableStateOf("chat") }
    var modelBackPage by rememberSaveable { mutableStateOf("chat") }
    var profiles by remember { mutableStateOf(store.profiles()) }
    var provider by remember { mutableStateOf(store.string("provider", "openrouter")) }
    var routerProfile by remember { mutableStateOf(store.string("router_profile_$provider", if (RouterPresets.get(provider) != null) provider else "auto")) }
    var customProtocol by remember { mutableStateOf(store.string("protocol_$provider", ApiProtocols.defaultFor(provider))) }
    val protocol = ApiProtocols.resolve(provider, customProtocol)
    val providerName = profiles.firstOrNull { it.id == provider }?.name ?: Providers.name(provider)
    var reasoningFormat by remember(provider) { mutableStateOf(ReasoningFormat.load(store.string("reasoning_format_$provider"))) }
    var endpoint by remember { mutableStateOf(store.string("endpoint_$provider", Providers.endpoint(provider))) }
    var apiKey by remember { mutableStateOf(store.loadKey(provider)) }
    var model by remember { mutableStateOf(store.string("model_$provider", Providers.defaultModel(provider))) }
    var imageMode by remember { mutableStateOf(store.bool("image_mode_$provider", false)) }
    var imageApi by remember { mutableStateOf(store.bool("image_api_$provider", false)) }
    var mediaRoute by remember { mutableStateOf(store.string("route_$provider", if (imageMode) MediaRoute.IMAGE else MediaRoute.CHAT)) }
    var speechVoice by remember { mutableStateOf(store.string("speech_voice", "alloy")) }
    var videoSeconds by remember { mutableStateOf(store.string("video_seconds", "0")) }
    var videoRatio by remember { mutableStateOf(store.string("video_ratio", "auto")) }
    var web by remember { mutableStateOf(store.bool("web_$provider", false)) }
    var readLinks by remember { mutableStateOf(false) }
    var reasoning by remember { mutableStateOf(store.bool("reasoning_$provider", false)) }
    var systemPrompt by remember { mutableStateOf(store.string("system", "Ты внимательный AI-ассистент. Отвечай на языке пользователя.") ) }
    var maxTokens by remember { mutableStateOf(store.string("tokens", "4096")) }
    var temperature by remember { mutableStateOf(store.string("temperature", "1")) }
    var dark by remember { mutableStateOf(store.bool("dark", true)) }
    var appearance by remember { mutableStateOf(Appearance.load(store)) }
    val effectiveDark = if (appearance.systemTheme) isSystemInDarkTheme() else dark
    var input by chatState.input
    var pending by chatState.pending
    var busy by chatState.busy
    var error by chatState.error
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
            runCatching { buildAttachmentFromUri(context, uri) }
                .onFailure { error = "Не удалось прикрепить файл: ${it.message}" }.getOrNull()
        }
        pending = (pending + items).distinctBy { it.uri }
    }

    fun persist(next: List<Conversation>) = chatState.persist(next)
    fun updateChat(id: String, edit: (Conversation) -> Conversation) = chatState.updateChat(id, edit)
    fun stop() = chatState.stop()
    fun openChat(chat: Conversation) { chatState.openChat(chat); page = "chat" }
    fun createChat() { chatState.createChat(); page = "chat" }
    fun closeHistory(action: () -> Unit) { scope.launch { historyDrawer.close(); action() } }
    fun openModels() { modelBackPage = page; page = "models" }
    fun goBack() { page = if (page == "models") modelBackPage else "chat" }

    fun switchProvider(next: String) {
        stop()
        provider = next
        routerProfile = store.string("router_profile_$next", if (RouterPresets.get(next) != null) next else "auto")
        customProtocol = store.string("protocol_$next", ApiProtocols.defaultFor(next))
        store.set("provider", next)
        endpoint = store.string("endpoint_$next", Providers.endpoint(next))
        apiKey = store.loadKey(next)
        model = store.string("model_$next", Providers.defaultModel(next))
        web = store.bool("web_$next")
        reasoning = store.bool("reasoning_$next")
        imageMode = store.bool("image_mode_$next")
        imageApi = store.bool("image_api_$next")
        mediaRoute = store.string("route_$next", if (imageMode) MediaRoute.IMAGE else MediaRoute.CHAT)
    }

    fun catalogReasoningKey(modelId: String): String =
        "reasoning_capability_${chatState.catalogId(provider, protocol, endpoint, apiKey)}|$modelId"

    fun selectModel(item: CatalogModel) {
        if (!ApiProtocols.canRun(protocol, item.route) ||
            (protocol == ApiProtocols.GEMINI && item.route == MediaRoute.VIDEO && item.id.startsWith("veo-"))) {
            error = "Модель доступна в каталоге, но этот протокол не поддерживает её тип запроса"
            return
        }
        model = item.id
        store.set("model_label_$provider|$model", item.name)
        store.set(catalogReasoningKey(model), org.json.JSONObject()
            .put("available", item.reasoning).put("mandatory", item.reasoningMandatory)
            .put("effort", item.defaultReasoningEffort.orEmpty()).toString())
        imageMode = item.outputImage
        imageApi = item.imageApi
        mediaRoute = item.route
        if (mediaRoute == MediaRoute.SPEECH && item.supportedVoices.isNotEmpty() && speechVoice !in item.supportedVoices) {
            speechVoice = item.supportedVoices.first()
            store.set("speech_voice", speechVoice)
        }
        store.set("route_$provider", mediaRoute)
        store.set("model_$provider", model)
        store.set("image_mode_$provider", imageMode)
        store.set("image_api_$provider", imageApi)
        page = "chat"
    }

    fun send(text: String, regeneration: Boolean = false, replacement: List<Message>? = null) {
        if (busy) return
        if (apiKey.isBlank() && !Providers.isCustom(provider)) { error = "В настройках укажи API-ключ"; return }
        if (model.isBlank()) { error = "Подключи API и выбери модель из каталога"; return }
        if (!ApiProtocols.canRun(protocol, mediaRoute)) { error = "Протокол не поддерживает выбранный тип запроса"; return }
        val base = try { ApiConnection.baseUrl(endpoint) } catch (failure: Exception) {
            error = failure.message.orEmpty(); return
        }
        val tokens = maxTokens.toIntOrNull()
        val heat = temperature.replace(',', '.').toDoubleOrNull()
        if (tokens == null || tokens !in 1..128000 || heat == null || !heat.isFinite() || heat !in 0.0..2.0) {
            error = "Проверь настройки: токены 1–128000, температура 0–2"; return
        }
        val advertisedReasoning = chatState.cachedCatalog(chatState.catalogId(provider, protocol, endpoint, apiKey))
            ?.models?.firstOrNull { it.id == model }
        val capability = advertisedReasoning?.let {
            RouterReasoning(it.reasoning, it.reasoningMandatory, it.defaultReasoningEffort)
        } ?: runCatching {
            val saved = org.json.JSONObject(store.string(catalogReasoningKey(model)))
            RouterReasoning(saved.getBoolean("available"), saved.optBoolean("mandatory"),
                saved.optString("effort").ifBlank { null })
        }.getOrNull()
        chatState.send(text, GenerationSettings(protocol, base, apiKey.trim(), model.trim(), systemPrompt,
            web && ApiCapabilities.web(protocol, model), reasoning && ApiCapabilities.reasoning(protocol, model),
            tokens, heat, imageMode, imageApi, mediaRoute, speechVoice,
            videoSeconds.toIntOrNull()?.coerceIn(0, 60) ?: 0, videoRatio, routerProfile,
            readLinks = readLinks && mediaRoute == MediaRoute.CHAT,
            searchKey = if (web && mediaRoute == MediaRoute.CHAT) store.loadKey("brave_search") else "",
            routerReasoning = capability, reasoningFormat = reasoningFormat), regeneration, replacement)
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
        scope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val folder = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
                    val file = java.io.File(folder, "neura-${chat.id}.md").apply { writeText(content) }
                    androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/markdown"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Экспорт чата"))
            } catch (failure: Exception) { error = "Не удалось экспортировать чат: ${failure.message}" }
        }
    }

    val colorScheme = remember(effectiveDark, appearance.accent) { neuraColors(effectiveDark, appearance.accent) }

    LaunchedEffect(effectiveDark, appearance.accent) {
        val background = colorScheme.background.toArgb()
        val bars = if (effectiveDark) SystemBarStyle.dark(background) else SystemBarStyle.light(background, background)
        var host: android.content.Context = context
        while (host is android.content.ContextWrapper && host !is ComponentActivity) host = host.baseContext
        (host as? ComponentActivity)?.let { activity ->
            activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !effectiveDark
                isAppearanceLightNavigationBars = !effectiveDark
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                activity.window.isNavigationBarContrastEnforced = false
                activity.window.isStatusBarContrastEnforced = false
            }
        }
    }
    BackHandler(page != "chat") { goBack() }
    BackHandler(page == "chat" && historyDrawer.isOpen) { scope.launch { historyDrawer.close() } }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * appearance.textScale)) {
    MaterialTheme(colorScheme = colorScheme, typography = neuraTypography) {
        Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding(), color = MaterialTheme.colorScheme.background) {
            when (page) {
                "settings" -> screens.SaveableStateProvider("settings") { SettingsScreen(
                    provider, protocol, apiKey, endpoint, model, imageMode, web, reasoning,
                    systemPrompt, maxTokens, temperature, dark,
                    routerProfile = routerProfile,
                    reasoningFormat = reasoningFormat,
                    onReasoningFormat = { reasoningFormat = it; store.set("reasoning_format_$provider", it.name) },
                    onRouterProfile = { routerProfile = it; store.set("router_profile_$provider", it) },
                    appearance = appearance,
                    onAppearance = { appearance = it; it.save(store) },
                    profiles = profiles,
                    store = store,
                    onDeleteProfile = {
                        val removed = provider
                        switchProvider("openrouter")
                        store.removeProfile(removed)
                        profiles = store.profiles()
                    },
                    onBack = { page = "chat" },
                    onProvider = ::switchProvider,
                    onProtocol = { chosen ->
                        customProtocol = chosen
                        store.set("protocol_$provider", chosen)
                        if (!ApiProtocols.canRun(chosen, mediaRoute)) {
                            mediaRoute = MediaRoute.CHAT
                            imageMode = false; imageApi = false
                            store.set("route_$provider", mediaRoute)
                            store.set("image_mode_$provider", false)
                            store.set("image_api_$provider", false)
                        }
                    },
                    onSaveConnection = { draftEndpoint, draftKey ->
                        try {
                            val committedEndpoint = ApiConnection.baseUrl(draftEndpoint)
                            val committedKey = draftKey.trim()
                            store.saveConnection(committedEndpoint, committedKey, provider)
                            endpoint = committedEndpoint
                            apiKey = committedKey
                            openModels()
                        } catch (failure: Exception) {
                            Toast.makeText(context, failure.message, Toast.LENGTH_LONG).show()
                        }
                    },
                    onModelLibrary = ::openModels,
                    onImageMode = { imageMode = it; imageApi = it; mediaRoute = if (it) MediaRoute.IMAGE else MediaRoute.CHAT;
                        store.set("route_$provider", mediaRoute); store.set("image_mode_$provider", it); store.set("image_api_$provider", it) },
                    onWeb = { web = it; store.set("web_$provider", it) },
                    onReasoning = { reasoning = it; store.set("reasoning_$provider", it) },
                    onPrompt = { systemPrompt = it; store.set("system", it) },
                    onTokens = { maxTokens = it; store.set("tokens", it) },
                    onTemperature = { temperature = it; store.set("temperature", it) },
                    onTheme = { dark = it; store.set("dark", it) },
                    voice = speechVoice, onVoice = { speechVoice = it; store.set("speech_voice", it) },
                    videoSeconds = videoSeconds, onVideoSeconds = { videoSeconds = it; store.set("video_seconds", it) },
                    videoRatio = videoRatio, onVideoRatio = { videoRatio = it; store.set("video_ratio", it) }
                ) }
                "models" -> screens.SaveableStateProvider("model-library") {
                    val catalogId = chatState.catalogId(provider, protocol, endpoint, apiKey)
                    key(catalogId) {
                    ModelLibraryScreen(provider, providerName, protocol, endpoint, apiKey, model, store,
                    cachedCatalog = chatState.cachedCatalog(catalogId),
                    loadCatalog = { refresh -> chatState.loadCatalog(provider, protocol, endpoint, apiKey, refresh) },
                    onBack = ::goBack, onSelect = ::selectModel,
                    onManual = { id, route, dedicatedImage ->
                        model = id
                        mediaRoute = route
                        imageMode = route == MediaRoute.IMAGE
                        imageApi = imageMode && dedicatedImage
                        store.set("route_$provider", mediaRoute)
                        store.set("model_$provider", model)
                        store.set("image_mode_$provider", imageMode)
                        store.set("image_api_$provider", imageApi)
                        page = "chat"
                    }
                )
                }
                }
                else -> ModalNavigationDrawer(
                    drawerState = historyDrawer, gesturesEnabled = historyDrawer.isOpen,
                    drawerContent = {
                        HistoryDrawer(chats, currentId, onOpen = { chat ->
                            closeHistory { if (chat.id != currentId) openChat(chat) }
                        }, onNew = { closeHistory(::createChat) }, onSettings = { closeHistory { page = "settings" } },
                        onRename = { renameId = it.id; renameValue = it.title },
                        onDelete = { deleteId = it.id }, onShare = ::exportChat)
                    }
                ) { screens.SaveableStateProvider("chat") { ChatScreen(
                    showModel = appearance.showModel, autoScroll = appearance.autoScroll, compact = appearance.compact,
                    chat = chats.find { it.id == currentId }, providerName = providerName,
                    model = store.string("model_label_$provider|$model").ifBlank { model.substringAfterLast('/') },
                    mediaRoute = mediaRoute, web = web, reasoning = reasoning,
                    needsConnection = endpoint.isBlank() || (apiKey.isBlank() && !Providers.isCustom(provider)),
                    canSearch = ApiCapabilities.web(protocol, model), canReason = ApiCapabilities.reasoning(protocol, model),
                    input = input, onInput = { input = it }, pending = pending,
                    onPickFile = { filePicker.launch(arrayOf("*/*")) },
                    onRemoveFile = { removed -> pending = pending.filterNot { it.id == removed.id } },
                    busy = busy,
                    liveMessageId = chatState.liveMessageId.value,
                    liveModel = chatState.liveModel.value,
                    sendSequence = chatState.sendSequence.value,
                    streaming = { chatState.streaming.value },
                    streamingImages = { chatState.streamingImages.value },
                    streamingReasoning = { chatState.streamingReasoning.value },
                    reasoningRequested = chatState.reasoningRequested.value,
                    error = error, onClearError = { error = "" },
                    onRetry = { send("", regeneration = true) },
                    onOpenModels = ::openModels,
                    onOpenSettings = { page = "settings" },
                    onOpenHistory = { focusManager.clearFocus(); scope.launch { historyDrawer.open() } },
                    onNew = ::createChat,
                    onWeb = { web = it; store.set("web_$provider", it) },
                    readLinks = readLinks, onReadLinks = { readLinks = it },
                    onReasoning = { reasoning = it; store.set("reasoning_$provider", it) },
                    onSend = { send(it) }, onStop = ::stop,
                    onEdit = { item -> editingId = item.id; editingText = item.text },
                    onRegenerate = {
                        val c = chats.find { it.id == currentId }
                        if (c?.messages?.lastOrNull()?.role == "assistant") {
                            send("", regeneration = true, replacement = c.messages.dropLast(1))
                        }
                    }
                ) } }
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
                if (currentId == id) {
                    chats.firstOrNull()?.let { chatState.openChat(it) } ?: run { currentId = ""; input = ""; pending = emptyList() }
                }
                deleteId = null
            }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Отмена") } })
        if (editingId != null) AlertDialog(onDismissRequest = { editingId = null }, title = { Text("Изменить сообщение") },
            text = { OutlinedTextField(editingText, { editingText = it }, minLines = 3, maxLines = 8) },
            confirmButton = { TextButton(onClick = {
                val id = editingId!!
                val current = chats.find { it.id == currentId }
                val index = current?.messages?.indexOfFirst { it.id == id } ?: -1
                if (!busy && current != null && index >= 0 && editingText.isNotBlank()) {
                    val revised = current.messages.take(index) + current.messages[index].copy(text = editingText.trim())
                    editingId = null
                    send("", regeneration = true, replacement = revised)
                }
            }) { Text("Отправить заново") } },
            dismissButton = { TextButton(onClick = { editingId = null }) { Text("Отмена") } })
    }
}
}
