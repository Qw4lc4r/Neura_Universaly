package com.neura.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val backgroundDark = Color(0xFF111314)
private val surfaceDark = Color(0xFF191C1D)
private val accent = Color(0xFFC9D7CE)
private val mint = Color(0xFF8DADA0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(17, 19, 20)
        window.navigationBarColor = android.graphics.Color.rgb(17, 19, 20)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { NeuraApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeuraApp() {
    val context = LocalContext.current
    val store = remember { LocalStore(context.applicationContext) }
    val api = remember { UniversalApi() }
    var chats by remember { mutableStateOf(store.loadChats()) }
    var currentId by remember { mutableStateOf(chats.firstOrNull()?.id ?: "") }
    var screen by remember { mutableStateOf("chat") }
    var provider by remember { mutableStateOf(store.string("provider", "openai")) }
    var keyInput by remember { mutableStateOf(store.loadKey(provider)) }
    var endpoint by remember { mutableStateOf(store.string("endpoint_$provider", Providers.endpoint(provider))) }
    var providerMenu by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(store.string("model_$provider", Providers.defaultModel(provider))) }
    var systemPrompt by remember { mutableStateOf(store.string("system", "Ты полезный, внимательный AI-ассистент. Отвечай на языке пользователя.")) }
    var web by remember { mutableStateOf(store.bool("web_$provider")) }
    var reasoning by remember { mutableStateOf(store.bool("reasoning_$provider")) }
    var modelPicker by remember { mutableStateOf(false) }
    fun chooseProvider(value: String) {
        provider = value
        store.set("provider", value)
        keyInput = store.loadKey(value)
        endpoint = store.string("endpoint_$value", Providers.endpoint(value))
        model = store.string("model_$value", Providers.defaultModel(value))
        web = store.bool("web_$value")
        reasoning = store.bool("reasoning_$value")
    }
    var maxTokens by remember { mutableStateOf(store.string("tokens", "4096")) }
    var temperature by remember { mutableStateOf(store.string("temperature", "1")) }
    var isDark by remember { mutableStateOf(store.bool("dark", true)) }
    var input by remember { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var streaming by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var renameId by remember { mutableStateOf<String?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var editId by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val resolver = context.contentResolver
        val granted = mutableListOf<Attachment>()
        uris.forEach { uri ->
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            granted += buildAttachmentFromUri(context, uri)
        }
        pendingAttachments = (pendingAttachments + granted).distinctBy { it.uri }
    }
    val scheme = if (isDark) darkColorScheme(
        primary = accent, onPrimary = Color(0xFF19231E), background = backgroundDark, onBackground = Color(0xFFE3E8E4),
        surface = surfaceDark, onSurface = Color(0xFFE3E8E4), surfaceVariant = Color(0xFF272B2B),
        primaryContainer = Color(0xFF303B35), onPrimaryContainer = Color(0xFFE5F2E8),
        outlineVariant = Color(0xFF303635), secondary = mint
    ) else lightColorScheme(primary = Color(0xFF385B4B), secondary = Color(0xFF527464), background = Color(0xFFF7F8F5), surface = Color.White)

    fun persist(next: List<Conversation>) { chats = next.sortedByDescending { it.updatedAt }; store.saveChats(chats) }
    fun changeChat(chat: Conversation) { if (busy) activeJob?.cancel(); currentId = chat.id; streaming = ""; busy = false; error = ""; screen = "chat" }
    fun newChat() {
        if (busy) activeJob?.cancel()
        val chat = Conversation()
        persist(listOf(chat) + chats); changeChat(chat); input = ""; pendingAttachments = emptyList()
    }
    fun updateChat(chatId: String, transform: (Conversation) -> Conversation) {
        persist(chats.map { if (it.id == chatId) transform(it).copy(updatedAt = System.currentTimeMillis()) else it })
    }
    fun send(text: String, regeneration: Boolean = false) {
        if (busy) return
        if (keyInput.isBlank() && provider != "custom") { error = "Укажи API-ключ в настройках"; return }
        if (model.isBlank()) { error = "Укажи ID модели"; return }
        if (!regeneration && text.isBlank() && pendingAttachments.isEmpty()) return
        var id = currentId
        if (chats.none { it.id == id }) {
            val chat = Conversation(); persist(listOf(chat) + chats); id = chat.id; currentId = id
        }
        val before = chats.first { it.id == id }
        val userMessage = Message(role = "user", text = text.trim(), attachments = pendingAttachments)
        val history = if (regeneration) before.messages else before.messages + userMessage
        if (!regeneration) {
            input = ""
            pendingAttachments = emptyList()
            updateChat(id) { it.copy(title = if (it.messages.isEmpty()) (text.ifBlank { "Сообщение с вложениями" }).trim().take(48) else it.title, messages = history) }
        }
        error = ""; streaming = ""; busy = true
        val targetId = id
        activeJob = scope.launch {
            val key = keyInput.trim(); val selectedModel = model.trim()
            try {
                api.stream(
                    context.applicationContext,
                    provider,
                    endpoint,
                    key,
                    selectedModel,
                    systemPrompt,
                    web && ApiCapabilities.web(provider, selectedModel),
                    reasoning && ApiCapabilities.reasoning(provider, selectedModel),
                    maxTokens.toIntOrNull()?.coerceIn(1, 32000) ?: 4096,
                    temperature.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 1.0,
                    history
                ) { delta -> withContext(Dispatchers.Main) { streaming += delta } }
                val result = streaming
                if (result.isNotBlank()) updateChat(targetId) { it.copy(messages = it.messages + Message(role = "assistant", text = result)) }
                else error = "Модель завершила ответ без текста"
            } catch (e: CancellationException) {
                val partial = streaming
                if (partial.isNotBlank()) updateChat(targetId) { it.copy(messages = it.messages + Message(role = "assistant", text = partial)) }
            } catch (e: Exception) {
                val partial = streaming
                if (partial.isNotBlank()) updateChat(targetId) { it.copy(messages = it.messages + Message(role = "assistant", text = partial)) }
                error = e.message ?: "Не удалось получить ответ"
            } finally { streaming = ""; busy = false; activeJob = null }
        }
    }
    fun exportChat(chat: Conversation) {
        val content = buildString {
            append("# ").append(chat.title).append("\n\n")
            chat.messages.forEach {
                append("## ").append(if (it.role == "user") "Вы" else "AI").append("\n\n")
                append(it.text).append("\n")
                if (it.attachments.isNotEmpty()) {
                    append("\nВложения:\n")
                    it.attachments.forEach { a -> append("- ").append(a.name).append(" (").append(a.mimeType).append(")\n") }
                }
                append("\n")
            }
        }
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, content); putExtra(Intent.EXTRA_SUBJECT, chat.title)
        }, "Экспорт чата"))
    }
    val selected = chats.firstOrNull { it.id == currentId }
    val listState = rememberLazyListState()
    val totalCount = (selected?.messages?.size ?: 0) + (if (busy) 1 else 0)
    LaunchedEffect(currentId, totalCount, streaming.length / 80) {
        if (totalCount > 0 && screen == "chat") listState.animateScrollToItem(totalCount - 1)
    }
    BackHandler(screen != "chat") { screen = "chat" }

    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding(), color = MaterialTheme.colorScheme.background) {
            when (screen) {
                "list" -> Column {
                    Header("История чатов", onBack = { screen = "chat" }, action = {
                        IconButton(onClick = { screen = "settings" }) { Icon(Icons.Default.Settings, "Настройки") }
                    })
                    Button(onClick = { newChat() }, modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Новый чат")
                    }
                    if (chats.isEmpty()) EmptyLabel("Здесь появятся твои диалоги")
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(chats, key = { it.id }) { chat ->
                            Row(Modifier.fillMaxWidth().clickable { changeChat(chat) }.padding(start = 19.dp, top = 10.dp, bottom = 10.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Icon(Icons.Default.ChatBubbleOutline, null, modifier = Modifier.padding(11.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                    Text("${chat.messages.size} сообщений", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                var menu by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Действия") }
                                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; renameId = chat.id; renameValue = chat.title })
                                        DropdownMenuItem(text = { Text("Поделиться") }, onClick = { menu = false; exportChat(chat) })
                                        DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; deleteId = chat.id })
                                    }
                                }
                            }
                        }
                    }
                }
                "settings" -> Column {
                    Header("Настройки", onBack = { screen = "chat" })
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
                        SettingsHeading("Подключение", "Выбирай провайдера, свой ключ и модель")
                        Box {
                            OutlinedButton(onClick = { providerMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Hub, null)
                                Spacer(Modifier.width(10.dp))
                                Text("Провайдер: ${Providers.name(provider)}", modifier = Modifier.weight(1f))
                                Icon(Icons.Default.ArrowDropDown, null)
                            }
                            DropdownMenu(providerMenu, onDismissRequest = { providerMenu = false }) {
                                Providers.ids.forEach { id ->
                                    DropdownMenuItem(text = { Text(Providers.name(id)) }, onClick = {
                                        chooseProvider(id); providerMenu = false
                                    })
                                }
                            }
                        }
                        OutlinedTextField(endpoint, { endpoint = it; store.set("endpoint_$provider", it) },
                            label = { Text("HTTPS API endpoint") }, singleLine = true,
                            supportingText = { Text("Для OpenAI-совместимых API: URL базового API без /chat/completions") },
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp))
                        OutlinedTextField(keyInput, { keyInput = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("API-ключ · ${Providers.name(provider)}") }, visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Показать ключ") } },
                            shape = RoundedCornerShape(15.dp))
                        Button(onClick = {
                            runCatching { store.saveKey(keyInput.trim(), provider); keyInput = store.loadKey(provider) }
                                .onFailure { error = "Не удалось защитить ключ: ${it.message}" }
                        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Lock, null); Spacer(Modifier.width(8.dp)); Text("Сохранить ключ защищённо") }
                        Box {
                            OutlinedTextField(model, { model = it; store.set("model_$provider", it) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                                label = { Text("ID модели") }, supportingText = { Text("Введи ID вручную или выбери пример") }, shape = RoundedCornerShape(12.dp),
                                trailingIcon = { IconButton(onClick = { modelPicker = true }) { Icon(Icons.Default.ExpandMore, "Выбрать модель") } })
                            DropdownMenu(expanded = modelPicker, onDismissRequest = { modelPicker = false }) {
                                Providers.suggestions(provider).forEach { suggested ->
                                    DropdownMenuItem(text = { Text(suggested) }, onClick = { model = suggested; store.set("model_$provider", model); modelPicker = false })
                                }
                            }
                        }
                        SettingsHeading("Инструменты модели", "Доступность зависит от выбранного API и модели")
                        SettingSwitch("Поиск в интернете", if (ApiCapabilities.web(provider, model)) "Поиск силами провайдера · может тарифицироваться отдельно" else "Для этого API/модели поиск не подключён", web && ApiCapabilities.web(provider, model), enabled = ApiCapabilities.web(provider, model)) { web = it; store.set("web_$provider", it) }
                        SettingSwitch("Размышления", if (ApiCapabilities.reasoning(provider, model)) "Включает управляемые настройки reasoning API" else "Для этой модели нет безопасного универсального переключателя", reasoning && ApiCapabilities.reasoning(provider, model), enabled = ApiCapabilities.reasoning(provider, model)) { reasoning = it; store.set("reasoning_$provider", it) }
                        Text("Переключатели действительно изменяют API-запрос, но модель и ключ должны поддерживать выбранную функцию.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(systemPrompt, { systemPrompt = it; store.set("system", it) }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8,
                            label = { Text("Системный промпт") }, shape = RoundedCornerShape(15.dp))
                        OutlinedTextField(maxTokens, { maxTokens = it; store.set("tokens", it) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("Максимум токенов (до 32000)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(15.dp))
                        OutlinedTextField(temperature, { temperature = it; store.set("temperature", it) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("Температура (0–1)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(15.dp))
                        SettingsHeading("Внешний вид", "Подстрой под себя")
                        SettingSwitch("Тёмная тема", "Мягкая палитра Neura", isDark) { isDark = it; store.set("dark", it) }
                        Text("История хранится на устройстве. Ключи шифруются Android Keystore. Содержимое сообщений отправляется выбранному провайдеру. Используй только доверенные HTTPS endpoint. Подписка на чат-бота не заменяет API-ключ.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(26.dp))
                    }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { screen = "list" }) { Icon(Icons.Default.Menu, "История") }
                        Column(Modifier.weight(1f).clickable { screen = "settings" }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("neura", fontWeight = FontWeight.Medium, fontSize = 20.sp, letterSpacing = (-0.7).sp)
                            }
                            Text("${Providers.name(provider)} · $model", maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }

                        IconButton(onClick = { newChat() }) { Icon(Icons.Default.Add, "Новый чат") }
                        IconButton(onClick = { screen = "settings" }) { Icon(Icons.Default.Tune, "Настройки") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if ((selected?.messages?.isEmpty() != false) && !busy) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("n.", fontWeight = FontWeight.Light, fontSize = 58.sp, letterSpacing = (-3).sp, color = MaterialTheme.colorScheme.primary)
                                Text("Начни с вопроса", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                                Text("Без лишнего. Только диалог.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Suggestion("Напиши код на Python") { input = it }
                                Suggestion("Помоги разобраться в сложной теме") { input = it }
                                Suggestion("Предложи идеи для проекта") { input = it }
                            }
                        }
                    } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        items(selected?.messages ?: emptyList(), key = { it.id }) { message ->
                            MessageBubble(message, onCopy = { clipboard.setText(AnnotatedString(message.text)) },
                                onEdit = if (message.role == "user" && !busy) {{ editId = message.id; editText = message.text }} else null,
                                onRegenerate = if (message.role == "assistant" && !busy && message.id == selected?.messages?.lastOrNull()?.id) {{
                                    updateChat(currentId) { it.copy(messages = it.messages.dropLast(1)) }; send("", regeneration = true)
                                }} else null,
                                onShare = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, message.text)
                                }, "Поделиться")) })
                        }
                        if (busy) item { MessageBubble(Message(role = "assistant", text = streaming.ifBlank { "Думаю…" }), isStreaming = true) }
                    }
                    if (error.isNotBlank()) Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { error = "" }) { Icon(Icons.Default.Close, "Закрыть") }
                    }
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FeatureChip("Поиск", Icons.Default.TravelExplore, web && ApiCapabilities.web(provider, model), ApiCapabilities.web(provider, model)) { web = !web; store.set("web_$provider", web) }
                                FeatureChip("Думать", Icons.Default.Psychology, reasoning && ApiCapabilities.reasoning(provider, model), ApiCapabilities.reasoning(provider, model)) { reasoning = !reasoning; store.set("reasoning_$provider", reasoning) }
                                Spacer(Modifier.weight(1f))
                                Text(Providers.name(provider), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Spacer(Modifier.height(8.dp))
                            PendingAttachmentsRow(pendingAttachments) { remove -> pendingAttachments = pendingAttachments.filterNot { it.id == remove.id } }
                            if (pendingAttachments.isNotEmpty()) Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalIconButton(onClick = { attachmentPicker.launch(arrayOf("*/*")) }, modifier = Modifier.size(48.dp), enabled = !busy) {
                                    Icon(Icons.Default.AttachFile, contentDescription = "Вложить")
                                }
                                OutlinedTextField(input, { input = it }, modifier = Modifier.weight(1f),
                                    placeholder = { Text("Спроси что-нибудь…") }, shape = RoundedCornerShape(14.dp),
                                    maxLines = 6, minLines = 1, enabled = !busy)
                                FilledIconButton(onClick = {
                                    if (busy) activeJob?.cancel() else if (input.isNotBlank() || pendingAttachments.isNotEmpty()) send(input)
                                }, modifier = Modifier.size(48.dp), enabled = busy || input.isNotBlank() || pendingAttachments.isNotEmpty(), shape = CircleShape) {
                                    Icon(if (busy) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send, if (busy) "Остановить" else "Отправить")
                                }
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                if (busy) {
                                    CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp)
                                    Spacer(Modifier.width(7.dp))
                                }
                                Text(if (busy) "Ответ формируется · нажми стоп" else "Можно вкладывать изображения и файлы",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        if (renameId != null) AlertDialog(onDismissRequest = { renameId = null }, title = { Text("Переименовать чат") },
            text = { OutlinedTextField(renameValue, { renameValue = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { val id = renameId!!; updateChat(id) { it.copy(title = renameValue.trim().ifBlank { "Чат" }) }; renameId = null }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { renameId = null }) { Text("Отмена") } })
        if (deleteId != null) AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("Удалить диалог?") },
            text = { Text("Восстановить переписку не получится.") },
            confirmButton = { TextButton(onClick = {
                val id = deleteId!!; if (id == currentId && busy) activeJob?.cancel()
                persist(chats.filterNot { it.id == id }); if (id == currentId) currentId = chats.firstOrNull()?.id ?: ""
                deleteId = null
            }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Отмена") } })
        if (editId != null) AlertDialog(onDismissRequest = { editId = null }, title = { Text("Изменить сообщение") },
            text = { OutlinedTextField(editText, { editText = it }, minLines = 3, maxLines = 8) },
            confirmButton = { TextButton(onClick = {
                val id = editId!!; val current = chats.find { it.id == currentId }
                val index = current?.messages?.indexOfFirst { it.id == id } ?: -1
                if (index >= 0 && editText.isNotBlank()) {
                    updateChat(currentId) { it.copy(messages = it.messages.take(index) + it.messages[index].copy(text = editText.trim())) }
                    editId = null
                    send("", regeneration = true)
                }
            }) { Text("Сохранить и отправить") } },
            dismissButton = { TextButton(onClick = { editId = null }) { Text("Отмена") } })
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
        Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        action()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SettingsHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun EmptyLabel(text: String) = Box(Modifier.fillMaxWidth().padding(25.dp), contentAlignment = Alignment.Center) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Suggestion(label: String, action: (String) -> Unit) {
    OutlinedButton(onClick = { action(label) }, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.ArrowOutward, null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp)); Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MessageBubble(message: Message, isStreaming: Boolean = false, onCopy: (() -> Unit)? = null,
                          onEdit: (() -> Unit)? = null, onRegenerate: (() -> Unit)? = null, onShare: (() -> Unit)? = null) {
    val isUser = message.role == "user"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        if (!isUser) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                Text("n", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(7.dp))
            Text("AI", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            if (isStreaming) { Spacer(Modifier.width(8.dp)); CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp) }
        }
        Surface(shape = RoundedCornerShape(if (isUser) 16.dp else 6.dp), color = if (isUser) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background,
            modifier = if (isUser) Modifier.widthIn(max = 340.dp) else Modifier.fillMaxWidth()) {
            Column {
                if (message.text.isNotBlank()) SelectionContainer { MarkdownText(message.text, modifier = Modifier.padding(14.dp)) }
                MessageAttachments(message.attachments)
                if (!isUser) AssistantImageGallery(message.text)
            }
        }
        if (!isStreaming) Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            if (onCopy != null) TinyAction(Icons.Default.ContentCopy, "Копировать", onCopy)
            if (onEdit != null) TinyAction(Icons.Default.Edit, "Редактировать", onEdit)
            if (onRegenerate != null) TinyAction(Icons.Default.Refresh, "Перегенерировать", onRegenerate)
            if (onShare != null) TinyAction(Icons.Default.Share, "Поделиться", onShare)
        }
    }
}

@Composable
private fun TinyAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(icon, label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun FeatureChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean, supported: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = supported,
        shape = RoundedCornerShape(10.dp),
        color = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, modifier = Modifier.size(15.dp),
                tint = if (supported) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = if (supported) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
            if (enabled) Icon(Icons.Default.Check, null, modifier = Modifier.size(12.dp))
        }
    }
}
