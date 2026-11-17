package com.neura.app

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ChatScreen(
    chat: Conversation?, provider: String, model: String, imageMode: Boolean, web: Boolean, reasoning: Boolean,
    input: String, onInput: (String) -> Unit, pending: List<Attachment>,
    onPickFile: () -> Unit, onRemoveFile: (Attachment) -> Unit,
    busy: Boolean, streaming: String, streamingImages: List<Attachment>,
    error: String, onClearError: () -> Unit, onRetry: () -> Unit,
    onOpenModels: () -> Unit, onOpenSettings: () -> Unit, onOpenHistory: () -> Unit, onNew: () -> Unit,
    onWeb: (Boolean) -> Unit, onReasoning: (Boolean) -> Unit,
    onSend: (String) -> Unit, onStop: () -> Unit,
    onEdit: (Message) -> Unit, onRegenerate: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val messages = chat?.messages ?: emptyList()
    val state = rememberLazyListState()
    val itemCount = messages.size + if (busy) 1 else 0
    LaunchedEffect(chat?.id, itemCount, streaming.length / 140) {
        if (itemCount > 0) state.animateScrollToItem(itemCount - 1)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 10.dp, top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenHistory) { Icon(Icons.Default.Menu, "История", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            Column(Modifier.weight(1f).clickable(onClick = onOpenModels)) {
                Text("neura.", fontSize = 27.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp,
                    color = MaterialTheme.colorScheme.onSurface)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Providers.name(provider).uppercase(), style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                    Text("  /  $model", style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Default.ExpandMore, "Выбрать модель", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onNew) { Icon(Icons.Default.Add, "Новый чат") }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Tune, "Настройки") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (messages.isEmpty() && !busy) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 30.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    Text("N / 05", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, letterSpacing = 3.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(18.dp))
                    Text("С чего\nначнём?", fontSize = 49.sp, lineHeight = 55.sp,
                        fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, letterSpacing = (-1).sp)
                    Spacer(Modifier.height(1.dp))
                    Text("Один интерфейс. Любые модели. Твои ключи.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(22.dp))
                    DividerLabel("НАПРАВЛЕНИЯ")
                    PromptStarter("01", "Разобраться в сложной теме") { onInput(it) }
                    PromptStarter("02", "Написать или проверить код") { onInput(it) }
                    PromptStarter("03", "Создать изображение") { onInput(it) }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state,
                contentPadding = PaddingValues(horizontal = 17.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(23.dp)) {
                items(messages, key = { it.id }) { message ->
                    val isLast = message.id == messages.lastOrNull()?.id
                    PremiumMessage(message,
                        onCopy = { clipboard.setText(AnnotatedString(message.text)) },
                        onEdit = if (message.role == "user" && !busy) ({ onEdit(message) }) else null,
                        onRegenerate = if (message.role == "assistant" && isLast && !busy) onRegenerate else null,
                        onShare = {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, message.text)
                            }, "Поделиться"))
                        }
                    )
                }
                if (busy) item {
                    PremiumMessage(Message(role = "assistant", text = streaming.ifBlank { if (streamingImages.isEmpty()) "Создаю ответ…" else "" }, attachments = streamingImages), isStreaming = true)
                }
            }
        }
        if (error.isNotBlank()) {
            Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
                color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(13.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(9.dp))
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = onClearError, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Закрыть") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpenModels) { Text("Другая модель") }
                        OutlinedButton(onClick = onRetry, enabled = !busy && messages.lastOrNull()?.role == "user") { Text("Повторить") }
                    }
                }
            }
        }
        Composer(
            provider = provider, model = model, imageMode = imageMode, web = web, reasoning = reasoning,
            input = input, onInput = onInput, pending = pending, onPickFile = onPickFile, onRemoveFile = onRemoveFile,
            busy = busy, onSend = onSend, onStop = onStop, onWeb = onWeb, onReasoning = onReasoning
        )
    }
}

@Composable
private fun DividerLabel(label: String) {
    Text(label, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.5.sp)
}

@Composable
private fun PromptStarter(index: String, prompt: String, onClick: (String) -> Unit) {
    Surface(onClick = { onClick(prompt) }, color = Color.Transparent, shape = RoundedCornerShape(9.dp),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(index, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(18.dp))
            Text(prompt, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.Default.ArrowOutward, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun PremiumMessage(
    message: Message, isStreaming: Boolean = false,
    onCopy: (() -> Unit)? = null, onEdit: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null, onShare: (() -> Unit)? = null
) {
    val isUser = message.role == "user"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        if (!isUser) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                    Text("N", modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(10.dp))
                Text("NEURA", fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (isStreaming) {
                    Spacer(Modifier.width(10.dp))
                    CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp)
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(if (isUser) 19.dp else 4.dp),
            color = if (isUser) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
            modifier = if (isUser) Modifier.widthIn(max = 345.dp) else Modifier.fillMaxWidth()
        ) {
            Column {
                if (message.text.isNotBlank()) SelectionContainer {
                    MarkdownText(message.text, Modifier.padding(if (isUser) 15.dp else 5.dp))
                }
                MessageAttachments(message.attachments, allowDownload = !isUser)
                if (!isUser) AssistantImageGallery(message.text)
            }
        }
        if (!isStreaming) Row(horizontalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier.padding(top = 3.dp)) {
            if (onCopy != null) MicroAction(Icons.Default.ContentCopy, "Копировать", onCopy)
            if (onEdit != null) MicroAction(Icons.Default.Edit, "Изменить", onEdit)
            if (onRegenerate != null) MicroAction(Icons.Default.Refresh, "Повторить", onRegenerate)
            if (onShare != null) MicroAction(Icons.Default.Share, "Поделиться", onShare)
        }
    }
}

@Composable
private fun MicroAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, onClick: () -> Unit) {
    IconButton(onClick, modifier = Modifier.size(35.dp)) {
        Icon(icon, contentDescription = title, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Composer(
    provider: String, model: String, imageMode: Boolean, web: Boolean, reasoning: Boolean,
    input: String, onInput: (String) -> Unit, pending: List<Attachment>,
    onPickFile: () -> Unit, onRemoveFile: (Attachment) -> Unit,
    busy: Boolean, onSend: (String) -> Unit, onStop: () -> Unit,
    onWeb: (Boolean) -> Unit, onReasoning: (Boolean) -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, top = 12.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallModeChip("SEARCH", Icons.Default.TravelExplore, web, ApiCapabilities.web(provider, model), { onWeb(!web) })
                SmallModeChip("THINK", Icons.Default.Psychology, reasoning, ApiCapabilities.reasoning(provider, model), { onReasoning(!reasoning) })
                Spacer(Modifier.weight(1f))
                if (imageMode) Text("IMAGE MODE", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
            }
            PendingAttachmentsRow(pending, onRemoveFile)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                IconButton(onClick = onPickFile, enabled = !busy, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.AttachFile, "Вложить", tint = MaterialTheme.colorScheme.primary)
                }
                OutlinedTextField(input, onInput, modifier = Modifier.weight(1f), minLines = 1, maxLines = 5,
                    enabled = !busy, placeholder = { Text(if (imageMode) "Опиши изображение…" else "Сообщение для нейросети…") },
                    shape = RoundedCornerShape(14.dp))
                FilledIconButton(onClick = { if (busy) onStop() else onSend(input) },
                    modifier = Modifier.size(48.dp), enabled = busy || input.isNotBlank() || pending.isNotEmpty()) {
                    Icon(if (busy) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                        if (busy) "Остановить" else "Отправить")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text("NEURA  /  YOUR KEYS · YOUR MODELS", fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.7.sp)
            }
        }
    }
}

@Composable
private fun SmallModeChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean, supported: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = supported, shape = RoundedCornerShape(9.dp),
        color = if (checked && supported) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, Modifier.size(15.dp),
                tint = if (supported) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f))
            Text(label, fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 0.4.sp,
                color = if (supported) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f))
        }
    }
}

@Composable
fun HistoryScreen(
    chats: List<Conversation>, onBack: () -> Unit, onOpen: (Conversation) -> Unit,
    onNew: () -> Unit, onRename: (Conversation) -> Unit, onDelete: (Conversation) -> Unit,
    onShare: (Conversation) -> Unit
) {
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Column(Modifier.weight(1f)) {
                Text("ARCHIVE / LOCAL", fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.primary)
                Text("Мои диалоги", fontSize = 24.sp, fontWeight = FontWeight.Medium)
            }
            IconButton(onClick = onNew) { Icon(Icons.Default.Add, "Новый чат") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            placeholder = { Text("Поиск по истории…") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) },
            shape = RoundedCornerShape(13.dp))
        LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(chats.filter { it.title.contains(query, true) || it.messages.any { m -> m.text.contains(query, true) } }, key = { it.id }) { chat ->
                var menu by remember { mutableStateOf(false) }
                Surface(onClick = { onOpen(chat) }, shape = RoundedCornerShape(15.dp),
                    color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(chat.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(chat.messages.lastOrNull()?.text?.ifBlank { "Вложение" } ?: "Пустой диалог",
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${chat.messages.size} MSG", fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Действия") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; onRename(chat) })
                                DropdownMenuItem(text = { Text("Экспортировать") }, onClick = { menu = false; onShare(chat) })
                                DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; onDelete(chat) })
                            }
                        }
                    }
                }
            }
        }
    }
}
