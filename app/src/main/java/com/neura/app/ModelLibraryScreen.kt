package com.neura.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException

@Composable
fun ModelLibraryScreen(
    provider: String,
    endpoint: String,
    apiKey: String,
    currentModel: String,
    store: LocalStore,
    onBack: () -> Unit,
    onSelect: (CatalogModel) -> Unit,
    onManual: (String, Boolean) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("Все") }
    var freeOnly by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf("Название") }
    var sortMenu by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf("") }
    var manualImage by remember { mutableStateOf(false) }
    var manualOpen by remember { mutableStateOf(false) }
    var favorites by remember(provider) { mutableStateOf(store.string("favorites_$provider").split("|").filter { it.isNotBlank() }.toSet()) }
    var recent by remember(provider) { mutableStateOf(store.string("recent_$provider").split("|").filter { it.isNotBlank() }) }
    var models by remember { mutableStateOf<List<CatalogModel>>(emptyList()) }
    val context = LocalContext.current
    var notice by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }

    LaunchedEffect(provider, endpoint, apiKey, revision) {
        loading = true; error = ""; notice = ""
        try {
            val result = ModelCatalog(context.applicationContext).list(provider, endpoint, apiKey)
            models = result.models
            notice = result.notice
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Не удалось загрузить каталог"
            models = emptyList()
        } finally { loading = false }
    }

    fun availableInChat(model: CatalogModel): Boolean {
        // Non-chat API products are discoverable, but must not be sent to /chat/completions.
        val outputs = model.outputModalities
        return model.outputImage || "text" in outputs || outputs.isEmpty()
    }
    val filtered = remember(query, filter, freeOnly, sorting, models, favorites, recent) {
        models.filter { model ->
            val outputs = model.outputModalities
            val isAudio = outputs.any { it in setOf("audio", "speech", "transcription") }
            val isVideo = "video" in outputs
            val isOther = outputs.any { it in setOf("embeddings", "rerank", "decisions") } ||
                (outputs.isNotEmpty() && outputs.none { it in setOf("text", "image", "audio", "speech", "transcription", "video") })
            (query.isBlank() || model.name.contains(query, true) || model.id.contains(query, true) || model.description.contains(query, true)) &&
                (!freeOnly || model.free) &&
                when (filter) {
                    "Чат" -> "text" in outputs || (outputs.isEmpty() && !model.outputImage)
                    "Изображения" -> model.outputImage
                    "Vision" -> model.inputImage
                    "Reasoning" -> model.reasoning
                    "Аудио" -> isAudio
                    "Видео" -> isVideo
                    "Другие" -> isOther
                    "Избранное" -> model.id in favorites
                    else -> true
                }
        }.let { source ->
            when (sorting) {
                "Недавние" -> source.sortedWith(compareBy({ recent.indexOf(it.id).let { idx -> if (idx < 0) Int.MAX_VALUE else idx } }, { it.name }))
                "Новые" -> source.sortedByDescending { it.created }
                "Контекст" -> source.sortedByDescending { it.context }
                else -> source.sortedBy { it.name.lowercase() }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 18.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Column(Modifier.weight(1f)) {
                Text("MODEL INDEX", fontSize = 10.sp, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                Text("Библиотека моделей", fontSize = 22.sp, fontWeight = FontWeight.Medium)
            }
            IconButton(onClick = { revision++ }) { Icon(Icons.Default.Refresh, "Обновить") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Название, разработчик, ID…") },
                shape = RoundedCornerShape(14.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Все", "Чат", "Изображения", "Vision", "Reasoning", "Аудио", "Видео", "Другие", "Избранное").forEach { item ->
                    FilterChip(selected = filter == item, onClick = { filter = item }, label = { Text(item) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = freeOnly, onClick = { freeOnly = !freeOnly },
                    leadingIcon = if (freeOnly) { { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) } } else null,
                    label = { Text("Только бесплатные") })
                Text("Фильтр по стоимости", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (loading) "Загрузка каталога…" else "${filtered.size} из ${models.size} · ${Providers.name(provider)}",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    TextButton(onClick = { sortMenu = true }) { Icon(Icons.Default.Sort, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text(sorting) }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        listOf("Название", "Недавние", "Новые", "Контекст").forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = { sorting = option; sortMenu = false })
                        }
                    }
                }
            }
        }
        if (error.isNotBlank()) {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Каталог недоступен", fontWeight = FontWeight.SemiBold)
                    Text(error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { revision++ }) { Text("Повторить") }
                }
            }
        }
        if (notice.isNotBlank()) {
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(9.dp))
                    Text(notice, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { revision++ }) { Text("Обновить") }
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!loading && error.isBlank() && filtered.isEmpty()) {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                Text(
                    when {
                        provider == "openrouter" && filter == "Изображения" && freeOnly ->
                            "Не нашлось бесплатных генераторов в загруженном каталоге. Нажми «Обновить» и проверь фильтры."
                        query.isNotBlank() -> "Ничего не найдено. Попробуй другое название или убери фильтры."
                        else -> "В этой категории моделей не найдено. Попробуй «Все» или обнови каталог."
                    },
                    modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(filtered, key = { it.id }) { entry ->
                val current = currentModel == entry.id
                val selectable = availableInChat(entry)
                Surface(
                    onClick = {
                        recent = (listOf(entry.id) + recent.filterNot { it == entry.id }).take(12)
                        store.set("recent_$provider", recent.joinToString("|"))
                        onSelect(entry)
                    },
                    enabled = selectable,
                    shape = RoundedCornerShape(16.dp),
                    color = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(entry.id, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                                    maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = {
                                favorites = if (entry.id in favorites) favorites - entry.id else favorites + entry.id
                                store.set("favorites_$provider", favorites.joinToString("|"))
                            }) {
                                Icon(if (entry.id in favorites) Icons.Default.Star else Icons.Default.StarBorder,
                                    "Избранное", tint = if (entry.id in favorites) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (entry.description.isNotBlank()) Text(entry.description, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            if (entry.outputImage) MiniTag("IMAGE")
                            if ("text" in entry.outputModalities || entry.outputModalities.isEmpty()) MiniTag("CHAT")
                            if ("video" in entry.outputModalities) MiniTag("VIDEO")
                            if (entry.outputModalities.any { it in setOf("audio", "speech", "transcription") }) MiniTag("AUDIO")
                            if ("embeddings" in entry.outputModalities) MiniTag("EMBED")
                            if ("rerank" in entry.outputModalities) MiniTag("RERANK")
                            if ("decisions" in entry.outputModalities) MiniTag("DECISION")
                            if (entry.inputImage) MiniTag("VISION")
                            if (entry.reasoning) MiniTag("REASON")
                            if (entry.free) MiniTag("FREE")
                            if (entry.context > 0) MiniTag("${entry.context / 1000}K")
                        }
                        if (!selectable) Text(
                            "Доступно в каталоге · этот тип API ещё не подключён в Neura",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item {
                OutlinedButton(onClick = { manualOpen = !manualOpen }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Edit, null); Spacer(Modifier.width(8.dp)); Text("Указать модель вручную")
                }
                if (manualOpen) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(manual, { manual = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("ID модели") })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = manualImage, onCheckedChange = { manualImage = it })
                        Text("Режим генерации изображений")
                    }
                    Button(onClick = { onManual(manual.trim(), manualImage) }, enabled = manual.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Выбрать ID") }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun MiniTag(text: String) {
    Text(text, fontSize = 9.sp, letterSpacing = 0.4.sp, fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(end = 3.dp))
}
