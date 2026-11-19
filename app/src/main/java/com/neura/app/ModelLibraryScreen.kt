package com.neura.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelLibraryScreen(
    provider: String,
    providerName: String,
    protocol: String,
    endpoint: String,
    apiKey: String,
    currentModel: String,
    store: LocalStore,
    loadCatalog: suspend (Boolean) -> CatalogResult,
    cachedCatalog: CatalogResult?,
    onBack: () -> Unit,
    onSelect: (CatalogModel) -> Unit,
    onManual: (String, String, Boolean) -> Unit
) {
    var query by rememberSaveable(provider, protocol, endpoint) { mutableStateOf("") }
    var filter by rememberSaveable(provider, protocol, endpoint) { mutableStateOf("Все") }
    var freeOnly by rememberSaveable(provider, protocol, endpoint) { mutableStateOf(false) }
    var sorting by rememberSaveable(provider, protocol, endpoint) { mutableStateOf("Недавние") }
    var menu by remember { mutableStateOf(false) }
    var filtersOpen by remember { mutableStateOf(false) }
    var manualOpen by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<CatalogModel?>(null) }
    val connectionId = remember(provider, protocol, endpoint) {
        runCatching { ApiConnection.identity(protocol, endpoint) }.getOrDefault(provider)
    }
    var favorites by remember(connectionId) { mutableStateOf(store.string("favorites_$connectionId").split("|").filter { it.isNotBlank() }.toSet()) }
    var recent by remember(connectionId) { mutableStateOf(store.string("recent_$connectionId").split("|").filter { it.isNotBlank() }) }
    var models by remember { mutableStateOf(cachedCatalog?.models.orEmpty()) }
    var notice by remember { mutableStateOf(cachedCatalog?.notice.orEmpty()) }
    var loading by remember { mutableStateOf(cachedCatalog == null) }
    var error by remember { mutableStateOf("") }
    var refreshRequested by remember { mutableIntStateOf(0) }

    LaunchedEffect(provider, protocol, endpoint, apiKey, refreshRequested) {
        if (refreshRequested == 0 && cachedCatalog != null) return@LaunchedEffect
        loading = true
        error = ""
        try {
            val result = loadCatalog(refreshRequested != 0)
            models = result.models
            notice = result.notice
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Не удалось загрузить каталог"
        } finally {
            loading = false
        }
    }

    fun availableInChat(model: CatalogModel): Boolean {
        // Non-chat API products are discoverable, but must not be sent to /chat/completions.
        return ApiProtocols.canRun(protocol, model.route) &&
            !(protocol == ApiProtocols.GEMINI && model.route == MediaRoute.VIDEO && model.id.startsWith("veo-"))
    }
    val filtered = remember(query, filter, freeOnly, sorting, models, favorites, recent) {
        models.filter { model ->
            val outputs = model.outputModalities
            val isAudio = outputs.any { it in setOf("audio", "speech", "transcription") } ||
                model.route in setOf(MediaRoute.SPEECH, MediaRoute.TRANSCRIPTION)
            val isVideo = "video" in outputs || model.route == MediaRoute.VIDEO
            val isOther = model.route == MediaRoute.UNSUPPORTED || outputs.any { it in setOf("embeddings", "rerank", "decisions") } ||
                (outputs.isNotEmpty() && outputs.none { it in setOf("text", "image", "audio", "speech", "transcription", "video") })
            (query.isBlank() || model.name.contains(query, true) || model.id.contains(query, true) || model.description.contains(query, true)) &&
                (!freeOnly || model.free) &&
                when (filter) {
                    "Чат" -> model.route == MediaRoute.CHAT
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
                "Недавние" -> source.sortedWith(compareBy({ recent.indexOf(it.id).let { idx -> if (idx < 0) Int.MAX_VALUE else idx } }, { it.name.lowercase() }))
                "Новые" -> source.sortedByDescending { it.created }
                "Контекст" -> source.sortedByDescending { it.context }
                else -> source.sortedBy { it.name.lowercase() }
            }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
            ScreenHeader("Модели", onBack) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreHoriz, "Действия с каталогом") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Обновить список") }, leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            enabled = !loading, onClick = { menu = false; refreshRequested++ })
                        DropdownMenuItem(text = { Text("Указать ID модели") }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menu = false; manualOpen = true })
                    }
                }
            }
            SearchField(query, { query = it }, "Найти модель", Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp))
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (loading && models.isEmpty()) providerName else "$providerName · ${filtered.size}",
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val filterCount = (if (filter != "Все") 1 else 0) + (if (freeOnly) 1 else 0) + (if (sorting != "Недавние") 1 else 0)
                TextButton(onClick = { filtersOpen = true }) {
                    Icon(Icons.Default.Tune, null, Modifier.size(19.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (filterCount == 0) "Фильтры" else "Фильтры · $filterCount")
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, Modifier.padding(horizontal = 20.dp, vertical = 7.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (notice.isNotBlank()) Text(notice, Modifier.padding(horizontal = 20.dp, vertical = 7.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (!loading && filtered.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(when {
                            error.isNotBlank() -> "Каталог недоступен"
                            query.isNotBlank() || freeOnly || filter != "Все" -> "Модели не найдены"
                            else -> "Каталог пуст"
                        }, style = MaterialTheme.typography.titleMedium)
                        when {
                            error.isNotBlank() -> TextButton(onClick = { refreshRequested++ }) { Text("Повторить") }
                            query.isNotBlank() || freeOnly || filter != "Все" ->
                                TextButton(onClick = { query = ""; freeOnly = false; filter = "Все" }) { Text("Сбросить поиск и фильтры") }
                        }
                        if (models.isEmpty()) TextButton(onClick = { manualOpen = true }) { Text("Указать ID модели") }
                    }
                }
            } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 13.dp, vertical = 5.dp)) {
                items(filtered, key = { it.id }) { entry ->
                    ModelRow(entry, current = currentModel == entry.id, favorite = entry.id in favorites,
                        selectable = availableInChat(entry),
                        onSelect = {
                            recent = (listOf(entry.id) + recent.filterNot { it == entry.id }).take(12)
                            store.set("recent_$connectionId", recent.joinToString("|"))
                            query = ""
                            onSelect(entry)
                        },
                        onDetails = { details = entry },
                        onFavorite = {
                            favorites = if (entry.id in favorites) favorites - entry.id else favorites + entry.id
                            store.set("favorites_$connectionId", favorites.joinToString("|"))
                        })
                    HorizontalDivider(Modifier.padding(horizontal = 9.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .64f))
                }
            }
        }
    }
    if (filtersOpen) ModalBottomSheet(
        onDismissRequest = { filtersOpen = false }, containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Фильтры", style = MaterialTheme.typography.titleLarge)
            CatalogOptions("Тип модели", listOf("Все", "Чат", "Изображения", "Vision", "Reasoning", "Аудио", "Видео", "Другие", "Избранное"),
                filter, { filter = it }, label = {
                    when (it) {
                        "Vision" -> "Анализ изображений"
                        "Reasoning" -> "Рассуждения"
                        else -> it
                    }
                })
            CatalogOptions("Сортировка", listOf("Недавние", "Название", "Новые", "Контекст"), sorting, { sorting = it })
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Только бесплатные", Modifier.weight(1f))
                Switch(freeOnly, onCheckedChange = { freeOnly = it })
            }
            Button(onClick = { filtersOpen = false }, Modifier.fillMaxWidth()) { Text("Показать модели") }
        }
    }
    if (manualOpen) ManualModelDialog(protocol, onDismiss = { manualOpen = false }, onManual = onManual)
    details?.let { entry ->
        AlertDialog(
            onDismissRequest = { details = null }, title = { Text(entry.name) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(entry.id, style = MaterialTheme.typography.bodySmall)
                    if (entry.description.isNotBlank()) Text(entry.description)
                    priceSummary(entry)?.let { Text(it) }
                    if (entry.supportedVoices.isNotEmpty()) Text("Голоса · ${entry.supportedVoices.joinToString(", ")}")
                    if (!availableInChat(entry)) Text("Для этой модели нужен другой протокол или специализированный API.")
                }
            },
            confirmButton = { TextButton(onClick = { details = null }) { Text("Закрыть") } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogOptions(title: String, options: List<String>, selected: String, onSelect: (String) -> Unit, label: (String) -> String = { it }) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        options.forEach { option ->
            FilterChip(selected == option, onClick = { onSelect(option) }, label = { Text(label(option)) }, shape = RoundedCornerShape(30.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelRow(entry: CatalogModel, current: Boolean, favorite: Boolean, selectable: Boolean,
    onSelect: () -> Unit, onDetails: () -> Unit, onFavorite: () -> Unit) {
    val features = listOfNotNull(
        when (entry.route) {
            MediaRoute.IMAGE -> "Изображения"
            MediaRoute.SPEECH -> "Озвучка"
            MediaRoute.TRANSCRIPTION -> "Аудио → текст"
            MediaRoute.VIDEO -> "Видео"
            else -> null
        },
        "Фото".takeIf { entry.inputImage && entry.route == MediaRoute.CHAT },
        "Рассуждения".takeIf { entry.reasoning },
        "${entry.context / 1000} тыс.".takeIf { entry.context >= 1000 },
        "Бесплатно".takeIf { entry.free },
        "Недоступна в чате".takeUnless { selectable }
    )
    val price = remember(entry.pricePrompt, entry.priceCompletion, entry.free) { priceSummary(entry) }
    Surface(shape = RoundedCornerShape(14.dp), color = if (current) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).combinedClickable(
                onClick = if (selectable) onSelect else onDetails, onClickLabel = if (selectable) "Выбрать модель" else "Подробнее",
                onLongClick = onDetails, onLongClickLabel = "Сведения о модели", role = Role.Button
            ).heightIn(min = 65.dp).padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (current) Icon(Icons.Default.Check, "Текущая модель", Modifier.padding(end = 8.dp).size(19.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (features.isNotEmpty()) Text(features.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    else if (entry.id != entry.name) Text(entry.id, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (price != null) Text(price, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onFavorite) {
                Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                    if (favorite) "Убрать из избранного" else "В избранное", Modifier.size(20.dp),
                    tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun priceSummary(entry: CatalogModel): String? {
    val input = entry.pricePrompt ?: return null
    val output = entry.priceCompletion ?: return null
    if (entry.free || !input.isFinite() || !output.isFinite()) return null
    val formatted = java.text.DecimalFormat("0.00##", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.US))
    return "Вход $" + formatted.format(input * 1_000_000) + " · выход $" + formatted.format(output * 1_000_000) + " / млн"
}

@Composable
private fun ManualModelDialog(protocol: String, onDismiss: () -> Unit, onManual: (String, String, Boolean) -> Unit) {
    var id by rememberSaveable { mutableStateOf("") }
    var route by rememberSaveable { mutableStateOf(MediaRoute.CHAT) }
    var imageApi by rememberSaveable { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    val routes = listOf(MediaRoute.CHAT to "Чат", MediaRoute.IMAGE to "Изображение", MediaRoute.SPEECH to "Озвучка",
        MediaRoute.TRANSCRIPTION to "Расшифровка", MediaRoute.VIDEO to "Видео")
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Указать модель") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(id, { id = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("ID модели") })
                Box {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(routes.first { it.first == route }.second, Modifier.weight(1f)); Icon(Icons.Default.ExpandMore, null)
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        routes.filter { ApiProtocols.canRun(protocol, it.first) }.forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { route = value; menu = false })
                        }
                    }
                }
                if (route == MediaRoute.IMAGE && protocol in setOf(ApiProtocols.OPENROUTER, ApiProtocols.OPENAI, ApiProtocols.OPENAI_COMPAT)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(imageApi, onCheckedChange = { imageApi = it })
                        Text("Отдельный Image API")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = id.isNotBlank() && ApiProtocols.canRun(protocol, route), onClick = { onManual(id.trim(), route, imageApi) }) {
                Text("Выбрать")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
