package com.neura.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(
    provider: String, protocol: String, apiKey: String, endpoint: String, model: String,
    imageMode: Boolean, web: Boolean, reasoning: Boolean,
    webMode: String, systemPrompt: String, maxTokens: String, temperature: String, dark: Boolean,
    onBack: () -> Unit,
    onProvider: (String) -> Unit,
    onProtocol: (String) -> Unit,
    onKey: (String) -> Unit,
    onSaveKey: () -> Unit,
    onEndpoint: (String) -> Unit,
    onModelLibrary: () -> Unit,
    onImageMode: (Boolean) -> Unit,
    onWeb: (Boolean) -> Unit,
    onWebMode: (String) -> Unit,
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
    var protocolMenu by remember { mutableStateOf(false) }
    var webModeMenu by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf("Подключение") }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Column(Modifier.weight(1f)) {
                Text("PREFERENCES / 01", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.primary)
                Text("Настройки", fontSize = 25.sp, fontWeight = FontWeight.Medium)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Подключение", "Модель", "Диалог", "Внешний вид").forEachIndexed { index, name ->
                val active = section == name
                Surface(onClick = { section = name }, shape = RoundedCornerShape(12.dp),
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant)) {
                    Row(Modifier.padding(vertical = 12.dp, horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("0${index + 1}", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)) {
            when (section) {
                "Подключение" -> {
                    SettingsIntro("01", "Твои подключения", "Ключи хранятся на устройстве. Никакого аккаунта Neura.")
                    SettingsPanel {
                        Text("ПРОВАЙДЕР", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                        Box {
                            OutlinedButton(onClick = { providerMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Hub, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(Providers.name(provider), modifier = Modifier.weight(1f))
                                Icon(Icons.Default.KeyboardArrowDown, null)
                            }
                            DropdownMenu(providerMenu, onDismissRequest = { providerMenu = false }) {
                                Providers.ids.forEach { candidate ->
                                    DropdownMenuItem(text = { Text(Providers.name(candidate)) }, onClick = { onProvider(candidate); providerMenu = false })
                                }
                            }
                        }
                        Text("ПРОТОКОЛ ЗАПРОСОВ", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                        Box {
                            OutlinedButton(
                                onClick = { if (provider == "custom") protocolMenu = true },
                                enabled = provider == "custom", modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(ApiProtocols.label(protocol), modifier = Modifier.weight(1f))
                                if (provider == "custom") Icon(Icons.Default.KeyboardArrowDown, "Выбрать протокол")
                            }
                            DropdownMenu(protocolMenu, onDismissRequest = { protocolMenu = false }) {
                                ApiProtocols.customizable.forEach { item ->
                                    DropdownMenuItem(text = { Text(ApiProtocols.label(item)) }, onClick = {
                                        onProtocol(item)
                                        protocolMenu = false
                                    })
                                }
                            }
                        }
                        Text(
                            if (provider == "custom") "Выбери формат, который действительно реализует твой сервер. Адрес и ID модели сами по себе не определяют протокол."
                            else "Формат задаётся выбранным провайдером. Для собственного сервера выбери «Свой API». В списке поддержаны несколько протоколов.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(endpoint, onEndpoint, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Адрес API (HTTPS)") }, singleLine = true)
                        OutlinedTextField(apiKey, onKey, modifier = Modifier.fillMaxWidth(), label = { Text("API-ключ") }, singleLine = true,
                            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showKey = !showKey }) {
                                    Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Показать ключ")
                                }
                            })
                        Button(onClick = onSaveKey, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Lock, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp)); Text("Сохранить ключ")
                        }
                    }
                    Text("Доступны форматы OpenAI, OpenRouter, Gemini и Anthropic. Конкретные медиа-возможности зависят от сервера и модели.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                "Модель" -> {
                    SettingsIntro("02", "Управление моделью", "Выбор из живого каталога и настройки генерации.")
                    SettingsPanel {
                        Text("ТЕКУЩАЯ МОДЕЛЬ", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                        Text(model, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                        OutlinedButton(onClick = onModelLibrary, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp)); Text("Открыть библиотеку моделей")
                        }
                        HorizontalDivider()
                        SettingsSwitch("Режим изображения", "Использовать Image API, только для совместимых моделей", imageMode, onImageMode)
                        SettingsSwitch("Поиск в интернете", "Запрашивать реальный поиск через API выбранного роутера", web, onWeb)
                        if (provider == "custom" && protocol in setOf(ApiProtocols.OPENAI_COMPAT, ApiProtocols.OPENROUTER, ApiProtocols.OPENAI)) {
                            Text("СПОСОБ ВЕБ-ПОИСКА", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                            Box {
                                OutlinedButton(onClick = { webModeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text(WebSearchModes.label(webMode), modifier = Modifier.weight(1f))
                                    Icon(Icons.Default.KeyboardArrowDown, "Выбрать способ поиска")
                                }
                                DropdownMenu(webModeMenu, onDismissRequest = { webModeMenu = false }) {
                                    WebSearchModes.options(protocol).forEach { option ->
                                        DropdownMenuItem(text = { Text(WebSearchModes.label(option)) }, onClick = {
                                            onWebMode(option); webModeMenu = false
                                        })
                                    }
                                }
                            }
                            Text(
                                "OpenAI-совместимость не гарантирует интернет-поиск. " +
                                    "Авто: для OpenRouter — web plugin, для OpenAI — Responses, " +
                                    "для остальных — web_search_options. " +
                                    "Если сервер не поддерживает выбранный способ, попробуй другой или обратись к его документации. " +
                                    "Поиск может тарифицироваться отдельно.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        SettingsSwitch("Размышления", "Когда модель поддерживает reasoning", reasoning, onReasoning)
                    }
                    SettingsPanel {
                        Text("MEDIA / OUTPUT", fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary, letterSpacing = 1.4.sp)
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
                        OutlinedTextField(maxTokens, onTokens, Modifier.fillMaxWidth(), label = { Text("Максимум токенов") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(temperature, onTemperature, Modifier.fillMaxWidth(), label = { Text("Температура (0–1)") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    }
                }
                "Диалог" -> {
                    SettingsIntro("03", "Поведение ассистента", "Установи собственный характер и контекст.")
                    SettingsPanel {
                        OutlinedTextField(systemPrompt, onPrompt, modifier = Modifier.fillMaxWidth(), minLines = 7,
                            label = { Text("Системная инструкция") })
                        Text("Ответы, файлы и изображения отправляются напрямую выбранному API. История остаётся локально на устройстве.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                "Внешний вид" -> {
                    SettingsIntro("04", "Оформление", "Типографика, цвет и движение без лишних эффектов.")
                    SettingsPanel {
                        SettingsSwitch("Тёмная тема", "Графит / тёплый металл", dark, onTheme)
                        Text("NEURA / RELEASE 1.1", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 2.sp, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun SettingsIntro(number: String, title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("SECTION $number", fontSize = 10.sp, letterSpacing = 2.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary)
        Text(title, fontSize = 25.sp, fontWeight = FontWeight.Medium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun SettingsSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
