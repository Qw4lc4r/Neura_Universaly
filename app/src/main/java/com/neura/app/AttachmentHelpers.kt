package com.neura.app

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
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
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(attachment.uri), attachment.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, attachment.name))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Нет приложения для открытия файла", Toast.LENGTH_SHORT).show()
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

@Composable
fun MessageAttachments(attachments: List<Attachment>) {
    if (attachments.isEmpty()) return
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
        attachments.forEach { attachment ->
            if (isImageMime(attachment.mimeType)) {
                Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        AsyncImage(
                            model = attachment.uri,
                            contentDescription = attachment.name,
                            modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                            contentScale = ContentScale.Crop
                        )
                        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Image, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (attachment.size > 0) Text(humanFileSize(attachment.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
fun AssistantImageGallery(text: String) {
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
                        modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(MaterialTheme.colorScheme.surfaceVariant),
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
