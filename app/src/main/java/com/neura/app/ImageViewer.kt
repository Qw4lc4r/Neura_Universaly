package com.neura.app

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

data class ImagePreview(
    val uri: String,
    val name: String,
    val attachment: Attachment? = null
)

/** Full-screen, in-app image viewer shared by generated images, image links and user photos. */
@Composable
fun ImageViewerDialog(image: ImagePreview, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var scale by remember(image.uri) { mutableFloatStateOf(1f) }
    var offset by remember(image.uri) { mutableStateOf(Offset.Zero) }
    var controlsVisible by remember(image.uri) { mutableStateOf(true) }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    fun setScale(next: Float) {
        scale = next.coerceIn(1f, 6f)
        // Controls reset translation when returning to 100%.
        if (scale == 1f) offset = Offset.Zero
    }

    fun save() {
        runCatching {
            when (Uri.parse(image.uri).scheme?.lowercase()) {
                "http", "https" -> saveRemoteImage(context, image.uri)
                "file", "content" -> saveGeneratedImage(
                    context,
                    image.attachment ?: Attachment(
                        name = image.name.ifBlank { "neura-image-${System.currentTimeMillis()}.png" },
                        uri = image.uri,
                        mimeType = "image/png"
                    )
                )
                else -> error("Нельзя сохранить изображение из этого источника")
            }
        }.onFailure {
            Toast.makeText(context, it.message ?: "Не удалось сохранить изображение", Toast.LENGTH_LONG).show()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xFF080A0B))) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .pointerInput(image.uri) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 6f)
                            val maxX = size.width.toFloat() * (nextScale - 1f) / 2f
                            val maxY = size.height.toFloat() * (nextScale - 1f) / 2f
                            scale = nextScale
                            offset = if (nextScale == 1f) Offset.Zero else Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY)
                            )
                        }
                    }
                    .pointerInput(image.uri) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) reset() else setScale(2.5f)
                            },
                            onTap = { controlsVisible = !controlsVisible }
                        )
                    }
            ) {
                AsyncImage(
                    model = image.uri,
                    contentDescription = image.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                )
            }

            if (controlsVisible) {
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter)
                        .background(Color(0xE5121415))
                        .statusBarsPadding()
                        .padding(start = 8.dp, end = 9.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.White)
                    }
                    Text(
                        text = image.name,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color(0xFFEAE8E4),
                        fontSize = 13.sp
                    )
                    Text(
                        text = "${(scale * 100).toInt()}%",
                        color = Color(0xFFC9B18B),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color(0xE5121415))
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { setScale(scale - 0.5f) }) {
                            Icon(Icons.Default.Remove, contentDescription = "Уменьшить", tint = Color.White)
                        }
                        IconButton(onClick = { setScale(scale + 0.5f) }) {
                            Icon(Icons.Default.Add, contentDescription = "Увеличить", tint = Color.White)
                        }
                        IconButton(onClick = { reset() }) {
                            Icon(Icons.Default.RestartAlt, contentDescription = "Сбросить масштаб", tint = Color.White)
                        }
                    }
                    IconButton(onClick = { save() }) {
                        Icon(Icons.Default.Download, contentDescription = "Сохранить изображение", tint = Color(0xFFC9B18B))
                    }
                }
            }
        }
    }
}
