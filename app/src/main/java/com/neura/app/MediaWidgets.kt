package com.neura.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider

private fun sharedUri(context: Context, attachment: Attachment): Uri {
    val source = Uri.parse(attachment.uri)
    return if (source.scheme == "file") {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", java.io.File(source.path!!))
    } else source
}

fun saveGeneratedMedia(context: Context, attachment: Attachment) {
    if (Build.VERSION.SDK_INT < 29) {
        Toast.makeText(context, "Сохранение медиа поддерживается на Android 10+", Toast.LENGTH_LONG).show()
        return
    }
    val isVideo = attachment.mimeType.startsWith("video/")
    val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val parent = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, attachment.name)
        put(MediaStore.MediaColumns.MIME_TYPE, attachment.mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, "$parent/Neura")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val result = resolver.insert(collection, values) ?: error("Нельзя создать медиафайл")
    try {
        resolver.openInputStream(Uri.parse(attachment.uri))?.use { source ->
            resolver.openOutputStream(result)?.use { target -> source.copyTo(target) }
                ?: error("Ошибка записи")
        } ?: error("Исходный файл не найден")
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(result, values, null, null)
        Toast.makeText(context, "Сохранено в $parent/Neura", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        resolver.delete(result, null, null)
        throw e
    }
}

private fun shareMedia(context: Context, attachment: Attachment) {
    val uri = sharedUri(context, attachment)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = attachment.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, "Поделиться файлом"))
}

@Composable
fun MediaAttachmentCard(attachment: Attachment, allowDownload: Boolean) {
    val context = LocalContext.current
    val video = attachment.mimeType.startsWith("video/")
    var openVideo by remember(attachment.uri) { mutableStateOf(false) }
    val audio = !video
    val player = remember(attachment.uri) {
        if (audio) runCatching { MediaPlayer.create(context, Uri.parse(attachment.uri)) }.getOrNull() else null
    }
    var playing by remember(attachment.uri) { mutableStateOf(false) }
    DisposableEffect(player) {
        player?.setOnCompletionListener { playing = false }
        onDispose { runCatching { player?.stop(); player?.release() } }
    }
    Surface(
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(if (video) Icons.Default.Movie else Icons.Default.GraphicEq,
                        contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(12.dp).size(23.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall)
                    Text("${if (video) "VIDEO" else "AUDIO"}  ·  ${humanFileSize(attachment.size)}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = {
                    if (video) openVideo = true else {
                        if (player != null) {
                            runCatching {
                                if (player.isPlaying) { player.pause(); playing = false }
                                else { player.start(); playing = true }
                            }.onFailure { Toast.makeText(context, "Не удалось воспроизвести", Toast.LENGTH_LONG).show() }
                        } else openAttachment(context, attachment)
                    }
                }) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(if (playing) "Пауза" else "Воспроизвести")
                }
                Spacer(Modifier.weight(1f))
                if (allowDownload && attachment.uri.startsWith("file:")) {
                    IconButton(onClick = {
                        runCatching { saveGeneratedMedia(context, attachment) }.onFailure {
                            Toast.makeText(context, it.message ?: "Не удалось сохранить", Toast.LENGTH_LONG).show()
                        }
                    }) { Icon(Icons.Default.Download, "Скачать") }
                }
                IconButton(onClick = {
                    runCatching { shareMedia(context, attachment) }.onFailure {
                        Toast.makeText(context, it.message ?: "Не удалось поделиться", Toast.LENGTH_LONG).show()
                    }
                }) { Icon(Icons.Default.Share, "Поделиться") }
            }
        }
    }
    if (openVideo) {
        Dialog(onDismissRequest = { openVideo = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(color = Color(0xFF060808), modifier = Modifier.fillMaxSize()) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("NEURA / CINEMA", color = Color.White, modifier = Modifier.weight(1f))
                        IconButton(onClick = { openVideo = false }) { Icon(Icons.Default.Close, "Закрыть", tint = Color.White) }
                    }
                    AndroidView(factory = { viewContext ->
                        VideoView(viewContext).apply {
                            val controller = MediaController(viewContext)
                            controller.setAnchorView(this)
                            setMediaController(controller)
                            setVideoURI(Uri.parse(attachment.uri))
                            setOnPreparedListener { start() }
                        }
                    }, modifier = Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}
