package com.neura.app

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import androidx.core.content.FileProvider
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID

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
    return urls.distinct()
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
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachments.forEach { attachment ->
            AssistChip(
                onClick = {},
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

fun saveGeneratedImage(context: Context, attachment: Attachment) {
    require(isImageMime(attachment.mimeType)) { "Неверный тип изображения" }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        Toast.makeText(context, "Сохранение в галерею поддерживается на Android 10+", Toast.LENGTH_LONG).show()
        return
    }
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, attachment.name)
        put(MediaStore.Images.Media.MIME_TYPE, attachment.mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Neura")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val destination = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("Не удалось создать файл изображения")
    try {
        resolver.openInputStream(Uri.parse(attachment.uri))?.use { input ->
            resolver.openOutputStream(destination)?.use { output -> input.copyTo(output) }
                ?: throw IllegalStateException("Не удалось открыть выходной файл")
        } ?: throw IllegalStateException("Изображение отсутствует")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(destination, values, null, null)
        Toast.makeText(context, "Сохранено в Изображения/Neura", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        resolver.delete(destination, null, null)
        throw e
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
        attachments.forEach { attachment ->
            if (attachment.mimeType.startsWith("audio/") || attachment.mimeType.startsWith("video/")) {
                MediaAttachmentCard(attachment, allowDownload)
            } else if (isImageMime(attachment.mimeType)) {
                Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        AsyncImage(
                            model = attachment.uri,
                            contentDescription = attachment.name,
                            modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f)
                                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onImageClick(ImagePreview(attachment.uri, attachment.name, attachment)) },
                            contentScale = ContentScale.Crop
                        )
                        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Image, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (attachment.size > 0) Text(humanFileSize(attachment.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (allowDownload && attachment.uri.startsWith("file:")) {
                                IconButton(onClick = {
                                    runCatching { saveGeneratedImage(context, attachment) }.onFailure {
                                        Toast.makeText(context, it.message ?: "Не удалось сохранить", Toast.LENGTH_LONG).show()
                                    }
                                }) { Icon(Icons.Default.Download, contentDescription = "Сохранить изображение") }
                            }
                            IconButton(onClick = { openAttachment(context, attachment) }) { Icon(Icons.Default.OpenInNew, null) }
                        }
                    }
                }
            } else {
                Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().clickable { openAttachment(context, attachment) }) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Description, null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(attachment.mimeType, humanFileSize(attachment.size)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.OpenInNew, null)
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
        urls.forEach { url ->
            Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column {
                    AsyncImage(
                        model = url,
                        contentDescription = "Картинка из ответа",
                        modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onImageClick(ImagePreview(url, "Изображение")) },
                        contentScale = ContentScale.Crop
                    )
                    Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Изображение", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { saveRemoteImage(context, url) }) { Icon(Icons.Default.Download, contentDescription = "Скачать") }
                    }
                }
            }
        }
    }
}
