package com.neura.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
    provider: String, apiKey: String, endpoint: String, model: String,
    imageMode: Boolean, web: Boolean, reasoning: Boolean,
    systemPrompt: String, maxTokens: String, temperature: String, dark: Boolean,
    onBack: () -> Unit,
    onProvider: (String) -> Unit,
    onKey: (String) -> Unit,
    onSaveKey: () -> Unit,
    onEndpoint: (String) -> Unit,
    onModelLibrary: () -> Unit,
    onImageMode: (Boolean) -> Unit,
    onWeb: (Boolean) -> Unit,
    onReasoning: (Boolean) -> Unit,
    onPrompt: (String) -> Unit,
    onTokens: (String) -> Unit,
    onTemperature: (String) -> Unit,
    onTheme: (Boolean) -> Unit
) {
    var providerMenu by remember { mutableStateOf(false) }
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Подключение", "Модель", "Диалог", "Внешний вид").forEach { name ->
                val active = section == name
                Surface(onClick = { section = name }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant)) {
                    Text(name, modifier = Modifier.padding(vertical = 11.dp, horizontal = 3.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurface)
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
                    Text("OpenAI, Claude, Gemini, OpenRouter и OpenAI-совместимые сервисы. Доступ к моделям зависит от выбранного API-ключа.",
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
                        SettingsSwitch("Поиск в интернете", "Когда провайдер и модель поддерживают инструмент", web, onWeb)
                        SettingsSwitch("Размышления", "Когда модель поддерживает reasoning", reasoning, onReasoning)
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
                    SettingsIntro("04", "Тихая роскошь", "Минимум элементов, акцент на содержании.")
                    SettingsPanel {
                        SettingsSwitch("Тёмная тема", "Графит / тёплый металл", dark, onTheme)
                        Text("Neura / EDITION 05", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary,
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
