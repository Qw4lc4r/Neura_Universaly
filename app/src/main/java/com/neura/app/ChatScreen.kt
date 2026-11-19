package com.neura.app

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    chat: Conversation?, providerName: String, model: String, mediaRoute: String, web: Boolean, reasoning: Boolean,
    needsConnection: Boolean, canSearch: Boolean, canReason: Boolean,
    input: String, onInput: (String) -> Unit, pending: List<Attachment>,
    onPickFile: () -> Unit, onRemoveFile: (Attachment) -> Unit,
    busy: Boolean, liveMessageId: String, liveModel: String, sendSequence: Long, streaming: () -> String, streamingImages: () -> List<Attachment>,
    error: String, onClearError: () -> Unit, onRetry: () -> Unit,
    onOpenModels: () -> Unit, onOpenSettings: () -> Unit, onOpenHistory: () -> Unit, onNew: () -> Unit,
    onWeb: (Boolean) -> Unit, onReasoning: (Boolean) -> Unit,
    readLinks: Boolean, onReadLinks: (Boolean) -> Unit,
    onSend: (String) -> Unit, onStop: () -> Unit,
    onEdit: (Message) -> Unit, onRegenerate: () -> Unit,
    reasoningRequested: Boolean = false, streamingReasoning: () -> String, showModel: Boolean = true, autoScroll: Boolean = true, compact: Boolean = false
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var imagePreview by remember { mutableStateOf<ImagePreview?>(null) }
    val messages = chat?.messages ?: emptyList()
    val state = rememberLazyListState()
    val newestFirst = remember(messages) { messages.asReversed() }
    val bottomIndex = if (autoScroll) 0 else messages.size + if (busy) 1 else 0
    val showJump by remember(state, autoScroll) {
        derivedStateOf { if (autoScroll) state.canScrollBackward else state.canScrollForward }
    }
    var lastFollowed by rememberSaveable { mutableLongStateOf(sendSequence) }
    var lastOpenedChat by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(sendSequence) {
        if (lastFollowed != sendSequence) {
            lastFollowed = sendSequence
            state.scrollToItem(bottomIndex)
        }
    }
    LaunchedEffect(chat?.id) {
        if (lastOpenedChat != chat?.id) {
            lastOpenedChat = chat?.id
            if (messages.isNotEmpty() || busy) state.scrollToItem(bottomIndex)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
        ChatHeader(providerName, model, messages.isNotEmpty() || busy, onOpenHistory, onOpenModels, onNew)
        if (messages.isEmpty() && !busy) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                if (maxHeight >= 110.dp) Column(Modifier.padding(horizontal = 26.dp)) {
                    if (needsConnection) {
                        Text("Начнём с подключения", style = MaterialTheme.typography.headlineMedium)
                        TextButton(onClick = onOpenSettings, contentPadding = PaddingValues(end = 12.dp)) {
                            Text("Настроить API"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                        }
                    } else Text(when (mediaRoute) {
                        MediaRoute.IMAGE -> "Что нарисуем?"
                        MediaRoute.SPEECH -> "Что озвучим?"
                        MediaRoute.TRANSCRIPTION -> "Расшифровка аудио"
                        MediaRoute.VIDEO -> "Что снимем?"
                        else -> "Что обсудим?"
                    }, style = MaterialTheme.typography.headlineLarge)
                }
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = state,
                reverseLayout = autoScroll,
                contentPadding = PaddingValues(horizontal = 19.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 13.dp else 25.dp)) {
                if (busy && autoScroll) item(key = liveMessageId) {
                    LiveReply(liveMessageId, liveModel, showModel, streaming, streamingImages, streamingReasoning, reasoningRequested) { imagePreview = it }
                }
                items(if (autoScroll) newestFirst else messages, key = { it.id }) { message ->
                    val isLast = message.id == messages.lastOrNull()?.id
                    PremiumMessage(message, showModel = showModel,
                        onImageClick = { imagePreview = it },
                        onCopy = { clipboard.setText(AnnotatedString(message.text)) },
                        onEdit = if (message.role == "user" && !busy) ({ onEdit(message) }) else null,
                        onRegenerate = if (message.role == "assistant" && isLast && !busy && mediaRoute != MediaRoute.VIDEO) onRegenerate else null,
                        onShare = {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, message.text)
                            }, "Поделиться"))
                        }
                    )
                }
                if (busy && !autoScroll) item(key = liveMessageId) {
                    LiveReply(liveMessageId, liveModel, showModel, streaming, streamingImages, streamingReasoning, reasoningRequested) { imagePreview = it }
                }
                if (!autoScroll) item(key = "bottom") { Spacer(Modifier.height(1.dp)) }
            }
            if (showJump) FilledTonalIconButton(
                onClick = { scope.launch { state.scrollToItem(bottomIndex) } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
            ) { Icon(Icons.Default.ArrowDownward, "К последнему сообщению", Modifier.size(20.dp)) }
            }
        }
        if (error.isNotBlank()) {
            Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
                color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(13.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(9.dp))
                        SelectionContainer(Modifier.weight(1f).heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                            Text(error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = onClearError, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Закрыть") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = if (needsConnection) onOpenSettings else onOpenModels,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                            Text(if (needsConnection) "Настроить API" else "Сменить модель")
                        }
                        if (!busy && !needsConnection && messages.lastOrNull()?.role == "user" && mediaRoute != MediaRoute.VIDEO)
                            TextButton(onClick = onRetry, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                                Text("Повторить")
                            }
                    }
                }
            }
        }
        Composer(
            mediaRoute = mediaRoute, web = web, reasoning = reasoning, canSearch = canSearch, canReason = canReason,
            input = input, onInput = onInput, pending = pending, onPickFile = onPickFile, onRemoveFile = onRemoveFile,
            busy = busy, onSend = onSend, onStop = onStop, onWeb = onWeb, onReasoning = onReasoning, readLinks = readLinks, onReadLinks = onReadLinks
        )
    }
    }
    imagePreview?.let { preview ->
        ImageViewerDialog(image = preview, onDismiss = { imagePreview = null })
    }
}

@Composable
private fun ChatHeader(providerName: String, model: String, hasChat: Boolean,
    onOpenHistory: () -> Unit, onOpenModels: () -> Unit, onNew: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onOpenHistory) { Icon(Icons.Default.Menu, "История и настройки") }
        Box(Modifier.weight(1f)) {
        Surface(onClick = onOpenModels, shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(model.ifBlank { "Выбрать модель" }, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(providerName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.ExpandMore, "Выбрать модель", Modifier.size(18.dp))
            }
        }
        }
        if (hasChat) IconButton(onClick = onNew) { Icon(Icons.Default.AddComment, "Новый чат", Modifier.size(22.dp)) }
    }
}

@Composable
private fun LiveReply(id: String, model: String, showModel: Boolean, text: () -> String, images: () -> List<Attachment>,
    thoughts: () -> String, reasoningRequested: Boolean, onImageClick: (ImagePreview) -> Unit) {
    PremiumMessage(
        Message(id = id, role = "assistant", text = text(), attachments = images(), model = model,
            reasoning = thoughts(), reasoningRequested = reasoningRequested),
        isStreaming = true, showModel = showModel, onImageClick = onImageClick
    )
}

@Composable
private fun PremiumMessage(
    message: Message, modifier: Modifier = Modifier, isStreaming: Boolean = false,
    onImageClick: (ImagePreview) -> Unit = {},
    onCopy: (() -> Unit)? = null, onEdit: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null, onShare: (() -> Unit)? = null, showModel: Boolean = true
) {
    val isUser = message.role == "user"
    Column(modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        if (!isUser) {
            if (isStreaming || showModel && message.model.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isStreaming) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.8.dp)
                    if (showModel && message.model.isNotBlank()) Text(message.model.substringAfterLast('/'),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else if (isStreaming && message.text.isBlank() && !message.reasoningRequested) Text("Ожидаю ответ…",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (message.status != "complete") Text(
                if (message.status == "stopped") "Остановлено · сохранён частичный ответ" else "Ответ не завершён · ошибка запроса",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 6.dp))
        }
        if (!isUser && (message.reasoning.isNotBlank() || message.reasoningRequested)) {
            var expanded by remember { mutableStateOf(false) }
            Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }
                        .heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Psychology, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (isStreaming && message.text.isEmpty()) {
                            if (message.reasoning.isNotBlank()) "Модель рассуждает…" else if (message.reasoningRequested) "Рассуждения включены · ожидаю модель…" else "Ожидаю ответ модели…"
                        } else "Рассуждения модели", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            if (expanded) "Скрыть рассуждения" else "Показать рассуждения")
                    }
                    if (expanded) {
                        SelectionContainer { Text(message.reasoning.ifBlank { "Модель ещё не передала текст рассуждений.".takeIf { isStreaming }
                            ?: "API не передал текст рассуждений." }, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Surface(
            shape = if (isUser) RoundedCornerShape(topStart = 19.dp, topEnd = 19.dp, bottomStart = 19.dp, bottomEnd = 6.dp)
                else RoundedCornerShape(4.dp),
            color = if (isUser) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            modifier = if (isUser) Modifier.widthIn(max = 345.dp) else Modifier.fillMaxWidth()
        ) {
            Column {
                if (message.text.isNotBlank()) SelectionContainer {
                    val textPadding = if (isUser) Modifier.padding(horizontal = 15.dp, vertical = 10.dp) else Modifier.padding(bottom = 2.dp)
                    if (isStreaming) Text(message.text, textPadding, style = MaterialTheme.typography.bodyLarge, lineHeight = 25.sp)
                    else MarkdownText(message.text, textPadding, hideRemoteImages = !isUser)
                }
                MessageAttachments(message.attachments, allowDownload = !isUser, onImageClick = onImageClick)
                if (!isUser && !isStreaming) AssistantImageGallery(message.text, onImageClick = onImageClick)
            }
        }
        if (!isStreaming && (onCopy != null || onEdit != null || onRegenerate != null || onShare != null)) {
            MessageActions(isUser, onCopy.takeIf { message.text.isNotBlank() }, onEdit, onRegenerate, onShare.takeIf { message.text.isNotBlank() })
        }
    }
}

@Composable
private fun MessageActions(isUser: Boolean, onCopy: (() -> Unit)?, onEdit: (() -> Unit)?,
    onRegenerate: (() -> Unit)?, onShare: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    Row {
        if (!isUser && onCopy != null) IconButton(onClick = onCopy) {
            Icon(Icons.Default.ContentCopy, "Копировать ответ", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if ((isUser && onCopy != null) || onEdit != null || onRegenerate != null || onShare != null) Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreHoriz, "Действия с сообщением", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                if (isUser && onCopy != null) DropdownMenuItem(text = { Text("Копировать") }, onClick = { menu = false; onCopy() })
                if (onEdit != null) DropdownMenuItem(text = { Text("Изменить") }, onClick = { menu = false; onEdit() })
                if (onRegenerate != null) DropdownMenuItem(text = { Text("Ответить заново") }, onClick = { menu = false; onRegenerate() })
                if (onShare != null) DropdownMenuItem(text = { Text("Поделиться") }, onClick = { menu = false; onShare() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(
    mediaRoute: String, web: Boolean, reasoning: Boolean, canSearch: Boolean, canReason: Boolean,
    input: String, onInput: (String) -> Unit, pending: List<Attachment>,
    onPickFile: () -> Unit, onRemoveFile: (Attachment) -> Unit,
    busy: Boolean, onSend: (String) -> Unit, onStop: () -> Unit,
    onWeb: (Boolean) -> Unit, onReasoning: (Boolean) -> Unit,
    readLinks: Boolean, onReadLinks: (Boolean) -> Unit
) {
    var toolsOpen by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val chatRoute = mediaRoute == MediaRoute.CHAT
    val activeModes = if (chatRoute) listOfNotNull(
        "Поиск".takeIf { web && canSearch },
        "Ссылки".takeIf { readLinks },
        "Рассуждения".takeIf { reasoning && canReason }
    ) else emptyList()
    val placeholder = when (mediaRoute) {
        MediaRoute.IMAGE -> "Опиши изображение"
        MediaRoute.SPEECH -> "Текст для озвучки"
        MediaRoute.TRANSCRIPTION -> "Прикрепи аудиофайл"
        MediaRoute.VIDEO -> "Опиши сцену"
        else -> "Сообщение"
    }

    Surface(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp),
        color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(23.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 5.dp, vertical = 4.dp)) {
            if (pending.isNotEmpty()) Box(Modifier.padding(start = 8.dp, top = 7.dp, end = 8.dp)) {
                PendingAttachmentsRow(pending, onRemoveFile)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { focusManager.clearFocus(); toolsOpen = true }, enabled = !busy) {
                    Icon(Icons.Default.Add, "Файлы и инструменты",
                        tint = if (activeModes.isEmpty() || busy) LocalContentColor.current else MaterialTheme.colorScheme.primary)
                }
                BasicTextField(
                    value = input, onValueChange = onInput,
                    modifier = Modifier.weight(1f).padding(horizontal = 3.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 5,
                    decorationBox = { field ->
                        Box(Modifier.heightIn(min = 48.dp).padding(vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                            if (input.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            field()
                        }
                    }
                )
                FilledIconButton(
                    onClick = { if (busy) onStop() else onSend(input) }, shape = CircleShape,
                    modifier = Modifier.size(48.dp), enabled = busy || input.isNotBlank() || pending.isNotEmpty()
                ) {
                    Icon(if (busy) Icons.Default.Stop else Icons.Default.ArrowUpward,
                        if (busy) "Остановить" else "Отправить", Modifier.size(22.dp))
                }
            }
            if (activeModes.isNotEmpty()) Text(
                activeModes.joinToString(" · "), Modifier.padding(start = 51.dp, end = 9.dp, bottom = 7.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary
            )
        }
    }
    if (toolsOpen) ModalBottomSheet(
        onDismissRequest = { toolsOpen = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            Text("Инструменты", Modifier.padding(start = 12.dp, bottom = 10.dp), style = MaterialTheme.typography.titleLarge)
            ToolRow(Icons.Default.AttachFile, "Фото и файлы", null, enabled = !busy) {
                toolsOpen = false
                onPickFile()
            }
            if (chatRoute) {
                HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                ToolRow(Icons.Default.TravelExplore, "Поиск в интернете", web, canSearch) { onWeb(!web) }
                ToolRow(Icons.Default.Link, "Прочитать ссылки", readLinks) { onReadLinks(!readLinks) }
                ToolRow(Icons.Default.Psychology, "Рассуждения", reasoning, canReason) { onReasoning(!reasoning) }
            }
        }
    }
}

@Composable
private fun ToolRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, checked: Boolean?,
    enabled: Boolean = true, onClick: () -> Unit
) {
    val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
    val interaction = if (checked == null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    else Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = { onClick() })
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).then(interaction)
        .heightIn(min = 54.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, Modifier.size(21.dp), tint = if (checked == true && enabled) MaterialTheme.colorScheme.primary else tint)
        Text(label, Modifier.weight(1f), color = tint)
        if (checked != null) Switch(checked, onCheckedChange = null, enabled = enabled) else
            Icon(Icons.Default.Add, null, Modifier.size(19.dp), tint = tint)
    }
}
