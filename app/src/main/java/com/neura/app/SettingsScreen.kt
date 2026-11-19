package com.neura.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    provider: String, protocol: String, apiKey: String, endpoint: String, model: String,
    imageMode: Boolean, web: Boolean, reasoning: Boolean,
    systemPrompt: String, maxTokens: String, temperature: String, dark: Boolean,
    routerProfile: String, onRouterProfile: (String) -> Unit,
    reasoningFormat: ReasoningFormat, onReasoningFormat: (ReasoningFormat) -> Unit,
    appearance: Appearance, onAppearance: (Appearance) -> Unit,
    profiles: List<ConnectionProfile>,
    store: LocalStore,
    onDeleteProfile: () -> Unit,
    onBack: () -> Unit,
    onProvider: (String) -> Unit,
    onProtocol: (String) -> Unit,
    onSaveConnection: (String, String) -> Unit,
    onModelLibrary: () -> Unit,
    onImageMode: (Boolean) -> Unit,
    onWeb: (Boolean) -> Unit,
    onReasoning: (Boolean) -> Unit,
    onPrompt: (String) -> Unit,
    onTokens: (String) -> Unit,
    onTemperature: (String) -> Unit,
    onTheme: (Boolean) -> Unit,
    voice: String, onVoice: (String) -> Unit,
    videoSeconds: String, onVideoSeconds: (String) -> Unit,
    videoRatio: String, onVideoRatio: (String) -> Unit
) {
    var providerMenu by remember { mutableStateOf(false) }
    var showKey by remember(provider) { mutableStateOf(false) }
    var draftEndpoint by remember(provider, endpoint) { mutableStateOf(endpoint) }
    var draftKey by remember(provider, endpoint, apiKey) { mutableStateOf(apiKey) }
    val savedPresetConnections = remember(provider) { RouterPresets.all
        .filter { it.id !in Providers.ids && (it.id == provider || store.loadKey(it.id).isNotBlank() || store.string("endpoint_${it.id}").isNotBlank()) }
        .map { ConnectionProfile(it.id, it.name) } }
    var showSaved by remember { mutableStateOf(false) }
    val savedConnections = profiles + savedPresetConnections
    var section by rememberSaveable { mutableStateOf("Подключение") }
    var deleteProfile by remember { mutableStateOf(false) }
    val endpointError = remember(draftEndpoint) { runCatching { ApiConnection.baseUrl(draftEndpoint) }.exceptionOrNull()?.message }
    val providerName = profiles.firstOrNull { it.id == provider }?.name ?: Providers.name(provider)

    if (showSaved) AlertDialog(onDismissRequest = { showSaved = false },
        title = { Text("Сохранённые подключения") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                savedConnections.forEach { saved ->
                    TextButton(onClick = { onProvider(saved.id); showSaved = false }) { Text(saved.name) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showSaved = false }) { Text("Закрыть") } })
    if (deleteProfile) AlertDialog(onDismissRequest = { deleteProfile = false },
        title = { Text("Удалить подключение?") },
        text = { Text("$providerName: адрес, ключ и настройки будут удалены. Переписки сохранятся.") },
        confirmButton = { TextButton(onClick = { onDeleteProfile(); deleteProfile = false }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { deleteProfile = false }) { Text("Отмена") } })

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
        ScreenHeader("Настройки", onBack)
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Подключение" to "API", "Модель" to "Модель", "Диалог" to "Диалог", "Внешний вид" to "Вид").forEach { (name, label) ->
                FilterChip(selected = section == name, onClick = { section = name }, label = { Text(label) },
                    shape = RoundedCornerShape(50))
            }
        }
        key(section) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)) {
            when (section) {
                "Подключение" -> {
                    SettingsPanel {
                        Text("Провайдер", style = MaterialTheme.typography.titleMedium)
                        Box {
                            OutlinedButton(onClick = { providerMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Hub, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(providerName, modifier = Modifier.weight(1f))
                                Icon(Icons.Default.KeyboardArrowDown, null)
                            }
                            DropdownMenu(providerMenu, onDismissRequest = { providerMenu = false }) {
                                Providers.ids.forEach { candidate ->
                                    DropdownMenuItem(text = { Text(Providers.name(candidate)) }, onClick = { onProvider(candidate); providerMenu = false })
                                }
                                if (savedConnections.isNotEmpty()) {
                                    HorizontalDivider()
                                    DropdownMenuItem(text = { Text("Сохранённые подключения") },
                                        onClick = { providerMenu = false; showSaved = true })
                                }
                            }
                        }
                        if (profiles.any { it.id == provider }) TextButton(onClick = { deleteProfile = true }) {
                            Text("Удалить сохранённое подключение")
                        }
                        OutlinedTextField(draftEndpoint, { next ->
                            draftEndpoint = next
                            if (draftKey == apiKey && !ApiConnection.sameOrigin(endpoint, next) &&
                                runCatching { ApiConnection.baseUrl(next) }.isSuccess) draftKey = ""
                        }, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Адрес API (HTTPS)") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            isError = draftEndpoint.isNotBlank() && endpointError != null,
                            supportingText = if (draftEndpoint.isNotBlank() && endpointError != null) {
                                { Text(endpointError) }
                            } else null)
                        OutlinedTextField(draftKey, { draftKey = it }, modifier = Modifier.fillMaxWidth(), label = { Text("API-ключ") }, singleLine = true,
                            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showKey = !showKey }) {
                                    Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Показать ключ")
                                }
                            })
                        Button(onClick = { onSaveConnection(draftEndpoint, draftKey) },
                            enabled = endpointError == null && (draftKey.isNotBlank() || Providers.isCustom(provider)), modifier = Modifier.fillMaxWidth()) {
                            Text("Сохранить и выбрать модель")
                        }
                        if (Providers.configurable(provider)) ApiCompatibility(
                            provider, protocol, draftEndpoint, routerProfile, reasoningFormat,
                            onProtocol, onRouterProfile, onReasoningFormat
                        )
                    }
                    AccountBalancePanel(provider, protocol, endpoint, apiKey, store)
                }
                "Модель" -> {
                    SettingsPanel {
                        Text("Текущая модель", style = MaterialTheme.typography.titleMedium)
                        Text(model.ifBlank { "Модель ещё не выбрана" }, style = MaterialTheme.typography.bodyLarge)
                        OutlinedButton(onClick = onModelLibrary, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp)); Text("Открыть библиотеку моделей")
                        }
                        HorizontalDivider()
                        SettingsSwitch("Режим изображения", "Использовать Image API, только для совместимых моделей", imageMode, onImageMode, ApiProtocols.canRun(protocol, MediaRoute.IMAGE))
                        SettingsSwitch("Поиск в интернете", "Параметры отправляются в формате выбранного протокола", web, onWeb, ApiCapabilities.web(protocol, model))
                        SettingsSwitch("Размышления", "Модели с обязательным thinking могут рассуждать всегда", reasoning, onReasoning, ApiCapabilities.reasoning(protocol, model))
                    }
                    SettingsPanel {
                        Text("Аудио и видео", style = MaterialTheme.typography.titleMedium)
                        Text("Для OpenRouter: голос и параметры видео. Не все модели принимают каждое значение.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(voice, onVoice, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("Голос для озвучки (например alloy)") })
                        OutlinedTextField(videoSeconds, onVideoSeconds, Modifier.fillMaxWidth(), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            label = { Text("Длительность видео · 0 = автоматически") })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Кадр видео", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            var ratioMenu by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { ratioMenu = true }) { Text(videoRatio); Icon(Icons.Default.ExpandMore, null) }
                                DropdownMenu(ratioMenu, onDismissRequest = { ratioMenu = false }) {
                                    listOf("16:9", "9:16", "1:1", "auto").forEach { choice ->
                                        DropdownMenuItem(text = { Text(choice) }, onClick = { onVideoRatio(choice); ratioMenu = false })
                                    }
                                }
                            }
                        }
                    }
                    SettingsPanel {
                        Text("Генерация", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(maxTokens, onTokens, Modifier.fillMaxWidth(), label = { Text("Максимум токенов") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            supportingText = { Text("Предел длины ответа. Роутер может использовать его при проверке стоимости запроса.") })
                        OutlinedTextField(temperature, onTemperature, Modifier.fillMaxWidth(), label = { Text("Температура (0–2)") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    }
                }
                "Диалог" -> {
                    SettingsPanel {
                        OutlinedTextField(systemPrompt, onPrompt, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 9,
                            label = { Text("Системная инструкция") })
                        Text("Ответы, файлы и изображения отправляются напрямую выбранному API. История остаётся локально на устройстве.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    WebSearchSettings(store)
                }
                "Внешний вид" -> {
                    SettingsPanel {
                        Text("Тема", style = MaterialTheme.typography.titleMedium)
                        SettingsSwitch("Системная тема", "Автоматически светлая или тёмная", appearance.systemTheme,
                            { onAppearance(appearance.copy(systemTheme = it)) })
                        if (!appearance.systemTheme) SettingsSwitch("Тёмная тема", "Мягкие тёмные поверхности", dark, onTheme)
                        Text("Акцентный цвет", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("moss" to "Мох", "violet" to "Ирис", "blue" to "Океан", "mint" to "Мята").forEach { (id, label) ->
                                FilterChip(appearance.accent == id, { onAppearance(appearance.copy(accent = id)) }, label = { Text(label) })
                            }
                        }
                        Text("Размер текста · ${(appearance.textScale * 100).toInt()}%")
                        Slider(appearance.textScale, { onAppearance(appearance.copy(textScale = it)) }, valueRange = .85f..1.3f, steps = 8)
                    }
                    SettingsPanel {
                        Text("Чат", style = MaterialTheme.typography.titleMedium)
                        SettingsSwitch("Компактные сообщения", "Меньше расстояния между ответами", appearance.compact,
                            { onAppearance(appearance.copy(compact = it)) })
                        SettingsSwitch("Название модели в ответах", "Показывать модель над сообщением", appearance.showModel,
                            { onAppearance(appearance.copy(showModel = it)) })
                        SettingsSwitch("Следовать за ответом", "Прокручивать чат во время генерации", appearance.autoScroll,
                            { onAppearance(appearance.copy(autoScroll = it)) })
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        } }
    }
    }
}

@Composable
private fun ApiCompatibility(
    provider: String, protocol: String, endpoint: String, profile: String, format: ReasoningFormat,
    onProtocol: (String) -> Unit, onProfile: (String) -> Unit, onFormat: (ReasoningFormat) -> Unit
) {
    var open by remember(provider) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text("Совместимость API")
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
    }
    if (open) {
        val profiles = listOf("auto" to "Авто по адресу API", "generic" to "Общий формат") + RouterPresets.all.map { it.id to it.name }
        ApiOption("Протокол", ApiProtocols.label(protocol), ApiProtocols.customizable.map { it to ApiProtocols.label(it) }, onProtocol)
        ApiOption("Профиль", profiles.firstOrNull { it.first == profile }?.second ?: "Авто по адресу API", profiles, onProfile)
        RouterPresets.resolve(profile, endpoint)?.note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (protocol in setOf(ApiProtocols.OPENAI_COMPAT, ApiProtocols.OPENROUTER)) {
            ApiOption("Параметр рассуждений", format.label, ReasoningFormat.entries.map { it to it.label }, onFormat)
        }
    }
}

@Composable
private fun <T> ApiOption(title: String, value: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Text(value, Modifier.weight(1f)); Icon(Icons.Default.ExpandMore, null)
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                options.forEach { (option, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; onSelect(option) })
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SettingsSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun WebSearchSettings(store: LocalStore) {
    var key by remember { mutableStateOf(store.loadKey("brave_search")) }
    var status by remember { mutableStateOf("") }
    SettingsPanel {
        Text("Поиск через Brave", style = MaterialTheme.typography.titleMedium)
        Text("С этим ключом поиск работает через Brave с любой чат-моделью. При включённом поиске текст текущего запроса отправляется в Brave.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(key, { key = it; status = "" }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Ключ Brave Search API") }, visualTransformation = PasswordVisualTransformation())
        TextButton(onClick = {
            status = runCatching { store.saveKey(key.trim(), "brave_search") }
                .fold({ if (key.isBlank()) "Ключ удалён" else "Ключ сохранён" }, { "Не удалось сохранить ключ" })
        }) { Text("Сохранить ключ поиска") }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AccountBalancePanel(provider: String, protocol: String, endpoint: String, key: String, store: LocalStore) {
    val scope = rememberCoroutineScope()
    var path by remember(provider) { mutableStateOf(store.string("balance_path_$provider")) }
    var field by remember(provider) { mutableStateOf(store.string("balance_field_$provider")) }
    var unit by remember(provider) { mutableStateOf(store.string("balance_unit_$provider")) }
    var expanded by remember(provider) { mutableStateOf(false) }
    var result by remember(provider, endpoint, key, path, field, unit) { mutableStateOf("") }
    var running by remember(provider, endpoint, key, path, field, unit) { mutableStateOf(false) }
    val openRouter = RouterPresets.detect(endpoint)?.id == "openrouter"
    SettingsPanel {
        Text("Баланс API", style = MaterialTheme.typography.titleMedium)
        Text(if (openRouter) "OpenRouter показывает остаток лимита ключа в долларах."
            else "Если роутер отдаёт остаток через GET API, укажи путь и поле из его документации.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Скрыть настройку баланса" else "Настроить адрес баланса")
        }
        if (expanded) {
            OutlinedTextField(path, { path = it; store.set("balance_path_$provider", it) }, Modifier.fillMaxWidth(),
                singleLine = true, label = { Text("Путь на том же сервере") }, placeholder = { Text("/v1/balance") })
            OutlinedTextField(field, { field = it; store.set("balance_field_$provider", it) }, Modifier.fillMaxWidth(),
                singleLine = true, label = { Text("JSON-поле") }, placeholder = { Text("data.remaining_tokens") })
            OutlinedTextField(unit, { unit = it; store.set("balance_unit_$provider", it) }, Modifier.fillMaxWidth(),
                singleLine = true, label = { Text("Единица измерения") }, placeholder = { Text("токенов") })
        }
        FilledTonalButton(
            enabled = !running && endpoint.isNotBlank() && (openRouter || path.isNotBlank() && field.isNotBlank()),
            onClick = {
                val resultState = ""
                result = resultState
                running = true
                scope.launch {
                    try {
                        result = RouterBalance.read(endpoint, key.trim(), protocol, path, field, unit)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        result = failure.message ?: "Не удалось получить баланс"
                    } finally {
                        running = false
                    }
                }
            }
        ) { Text(if (running) "Проверяю…" else "Обновить баланс") }
        if (result.isNotBlank()) Text(result, style = MaterialTheme.typography.bodyMedium)
    }
}

