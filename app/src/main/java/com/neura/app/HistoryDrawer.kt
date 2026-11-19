package com.neura.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun HistoryDrawer(
    chats: List<Conversation>, currentId: String, onOpen: (Conversation) -> Unit, onNew: () -> Unit,
    onSettings: () -> Unit, onRename: (Conversation) -> Unit, onDelete: (Conversation) -> Unit,
    onShare: (Conversation) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val today = LocalDate.now()
    val groups = remember(chats, query, today) {
        val zone = ZoneId.systemDefault()
        val search = query.trim()
        chats.asSequence().filter { it.messages.isNotEmpty() }.filter { chat ->
            search.isBlank() || chat.title.contains(search, true) || chat.messages.any { it.text.contains(search, true) }
        }.groupBy { chat ->
            val date = Instant.ofEpochMilli(chat.updatedAt).atZone(zone).toLocalDate()
            when {
                date >= today -> "Сегодня"
                date >= today.minusDays(6) -> "Последние 7 дней"
                else -> "Раньше"
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxHeight()) {
    ModalDrawerSheet(
        modifier = Modifier.width(minOf(344.dp, maxWidth * .9f)).fillMaxHeight(),
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
        windowInsets = WindowInsets(0, 0, 0, 0)
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            Text("Neura", Modifier.padding(start = 10.dp, top = 16.dp, bottom = 14.dp), style = MaterialTheme.typography.headlineMedium)
            FilledTonalButton(onClick = onNew, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Новый чат")
            }
            SearchField(query, { query = it }, "Найти диалог", Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
                if (groups.isEmpty()) item {
                    Text(if (query.isBlank()) "Здесь появятся ваши диалоги" else "Диалоги не найдены",
                        Modifier.padding(horizontal = 10.dp, vertical = 18.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                groups.forEach { (label, entries) ->
                    item(key = label) {
                        Text(label, Modifier.padding(start = 10.dp, top = 15.dp, bottom = 3.dp),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(entries, key = { it.id }) { chat ->
                        var menu by remember { mutableStateOf(false) }
                        val selected = chat.id == currentId
                        Surface(shape = RoundedCornerShape(12.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                    .clickable(role = Role.Button, onClick = { query = ""; onOpen(chat) }).heightIn(min = 48.dp)
                                    .padding(start = 10.dp, end = 2.dp), contentAlignment = Alignment.CenterStart) {
                                    Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = if (selected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
                                }
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreHoriz, "Действия с диалогом", Modifier.size(21.dp)) }
                                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
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
            HorizontalDivider()
            TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                contentPadding = PaddingValues(horizontal = 10.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                Icon(Icons.Default.Settings, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
                Text("Настройки", Modifier.weight(1f))
            }
        }
    }
    }
}
