package com.neura.app

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** System file picker works on every supported Android version, without storage permissions. */
@Composable
fun rememberAttachmentSaver(): (Attachment) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceUri by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { destination ->
        val source = sourceUri
        sourceUri = null
        if (destination != null && source != null) scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(Uri.parse(source))?.use { input ->
                        context.contentResolver.openOutputStream(destination)?.use { input.copyTo(it) }
                            ?: error("Не удалось открыть файл для записи")
                    } ?: error("Исходный файл не найден")
                }
            }
            Toast.makeText(context, result.fold({ "Файл сохранён" }, { "Ошибка сохранения: ${it.message}" }), Toast.LENGTH_LONG).show()
        }
    }
    return { attachment -> sourceUri = attachment.uri; launcher.launch(attachment.name) }
}
