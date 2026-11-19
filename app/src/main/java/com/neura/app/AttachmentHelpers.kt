package com.neura.app

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import android.os.Environment
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import okhttp3.Request
import java.io.File

fun isImageMime(mimeType: String): Boolean = mimeType.startsWith("image/")

fun humanFileSize(size: Long): String = when {
    size <= 0L -> ""
    size < 1024L -> "$size B"
    size < 1024L * 1024L -> "${size / 1024} KB"
    else -> "${size / (1024 * 1024)} MB"
}

fun buildAttachmentFromUri(context: Context, uri: Uri): Attachment {
    val resolver = context.contentResolver
    var name = "file"
    var size = 0L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
        }
    }
    val mime = resolver.getType(uri) ?: guessMimeFromName(name)
    return Attachment(name = name, uri = uri.toString(), mimeType = mime, size = size)
}

private fun guessMimeFromName(name: String): String {
    val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}

fun openAttachment(context: Context, attachment: Attachment) {
    try {
        val source = Uri.parse(attachment.uri)
        val uri = if (source.scheme == "file") {
            val path = source.path ?: throw IllegalArgumentException("Нет пути к файлу")
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", java.io.File(path))
        } else source
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, attachment.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, attachment.name))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Нет приложения для открытия файла", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, e.message ?: "Не удалось открыть файл", Toast.LENGTH_LONG).show()
    }
}

fun extractImageUrls(text: String): List<String> {
    val markdown = Regex("!\\[[^]]*\\]\\((https?://[^)]+)\\)")
    val direct = Regex("https?://\\S+?(?:png|jpg|jpeg|webp|gif)(?:\\?\\S*)?", RegexOption.IGNORE_CASE)
    val urls = mutableListOf<String>()
    markdown.findAll(text).forEach { urls += it.groupValues[1] }
    direct.findAll(text).forEach { urls += it.value }
    return urls.filter { it.startsWith("https://", ignoreCase = true) }.distinct().take(8)
}

fun saveRemoteImage(context: Context, url: String, suggestedName: String? = null) {
    val fileName = suggestedName ?: url.substringAfterLast('/').substringBefore('?').ifBlank { "image-${System.currentTimeMillis()}.png" }
    val request = DownloadManager.Request(Uri.parse(url))
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    manager.enqueue(request)
    Toast.makeText(context, "Скачивание началось", Toast.LENGTH_SHORT).show()
}

@Composable
fun PendingAttachmentsRow(attachments: List<Attachment>, onRemove: (Attachment) -> Unit) {
    if (attachments.isEmpty()) return
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachments.forEach { attachment ->
            if (isImageMime(attachment.mimeType)) {
                Box(Modifier.size(80.dp)) {
                    AsyncImage(attachment.uri, "Прикреплённое изображение",
                        modifier = Modifier.size(76.dp).clip(RoundedCornerShape(16.dp)).clickable { openAttachment(context, attachment) },
                        contentScale = ContentScale.Crop)
                    Surface(Modifier.align(Alignment.TopEnd), shape = CircleShape, color = MaterialTheme.colorScheme.surface) {
                        IconButton(onClick = { onRemove(attachment) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, "Убрать изображение", Modifier.size(16.dp))
                        }
                    }
                }
            } else AssistChip(
                onClick = { openAttachment(context, attachment) },
                label = {
                    Text(
                        text = attachment.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(if (isImageMime(attachment.mimeType)) Icons.Default.Image else Icons.Default.Description, contentDescription = null)
                },
                trailingIcon = {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Убрать",
                        modifier = Modifier.clickable { onRemove(attachment) }
                    )
                }
            )
        }
    }
}

@Composable
fun MessageAttachments(
    attachments: List<Attachment>,
    allowDownload: Boolean = false,
    onImageClick: (ImagePreview) -> Unit = {}
) {
    if (attachments.isEmpty()) return
    val context = LocalContext.current
    val saveAttachment = rememberAttachmentSaver()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
        attachments.forEach { attachment ->
            if (attachment.mimeType.startsWith("audio/") || attachment.mimeType.startsWith("video/")) {
                MediaAttachmentCard(attachment, allowDownload)
            } else if (isImageMime(attachment.mimeType)) {
                ChatImage(attachment.uri, onClick = { onImageClick(ImagePreview(attachment.uri, "Изображение", attachment)) },
                    onSave = if (allowDownload) ({ saveAttachment(attachment) }) else null)
            } else {
                Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().clickable { openAttachment(context, attachment) }) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Description, null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(attachment.mimeType, humanFileSize(attachment.size)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
                    }
                }
            }
        }
    }
}

@Composable
fun AssistantImageGallery(text: String, onImageClick: (ImagePreview) -> Unit = {}) {
    val urls = remember(text) { extractImageUrls(text) }
    if (urls.isEmpty()) return
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(4.dp)) {
        urls.forEach { url ->
            key(url) {
                var loaded by remember { mutableStateOf(false) }
                if (!loaded) androidx.compose.material3.FilledTonalButton(onClick = { loaded = true }) {
                    Icon(Icons.Default.Image, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Загрузить изображение")
                } else ChatImage(url, onClick = { onImageClick(ImagePreview(url, "Изображение")) },
                    onSave = { runCatching { saveRemoteImage(context, url) }.onFailure {
                        Toast.makeText(context, it.message, Toast.LENGTH_LONG).show()
                    } })
            }
        }
    }
}

@Composable
private fun ChatImage(uri: String, onClick: () -> Unit, onSave: (() -> Unit)? = null) {
    var ratio by remember(uri) { mutableFloatStateOf(1f) }
    Box(Modifier.widthIn(max = 360.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)) {
        AsyncImage(uri, "Изображение в чате", modifier = Modifier.fillMaxWidth()
            .aspectRatio(ratio.coerceIn(.55f, 2.2f)).clickable(onClick = onClick),
            contentScale = ContentScale.Fit,
            onSuccess = { state ->
                val image = state.result.drawable
                if (image.intrinsicWidth > 0 && image.intrinsicHeight > 0)
                    ratio = image.intrinsicWidth.toFloat() / image.intrinsicHeight
            })
        if (onSave != null) Surface(Modifier.align(Alignment.BottomEnd).padding(8.dp), shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = .9f)) {
            IconButton(onClick = onSave) { Icon(Icons.Default.Download, "Сохранить изображение", Modifier.size(20.dp)) }
        }
    }
}
