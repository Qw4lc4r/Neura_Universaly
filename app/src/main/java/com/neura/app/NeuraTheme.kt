package com.neura.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun neuraColors(dark: Boolean, accent: String): ColorScheme {
    val (primary, container) = when (accent) {
        "blue" -> if (dark) Color(0xFFAFC8F6) to Color(0xFF28364E) else Color(0xFF305F9E) to Color(0xFFE1EAF8)
        "mint" -> if (dark) Color(0xFF9EDBC9) to Color(0xFF213C33) else Color(0xFF216F5D) to Color(0xFFDDEFE7)
        "violet" -> if (dark) Color(0xFFD2C0F5) to Color(0xFF3A2F4C) else Color(0xFF674990) to Color(0xFFF0E7F7)
        else -> if (dark) Color(0xFFB9D6A6) to Color(0xFF303E2A) else Color(0xFF3C6235) to Color(0xFFE4EDDA)
    }
    return if (dark) darkColorScheme(
        primary = primary, onPrimary = Color(0xFF19231A), primaryContainer = container, onPrimaryContainer = primary,
        secondary = primary, secondaryContainer = Color(0xFF31362E), onSecondaryContainer = Color(0xFFE0E6D9),
        background = Color(0xFF181A17), onBackground = Color(0xFFECEDE4), surface = Color(0xFF21241F),
        onSurface = Color(0xFFECEDE4), surfaceVariant = Color(0xFF2B3029), onSurfaceVariant = Color(0xFFB3BAAD),
        surfaceContainerLowest = Color(0xFF151713), surfaceContainerLow = Color(0xFF1E211C),
        surfaceContainer = Color(0xFF252922), surfaceContainerHigh = Color(0xFF2D322A),
        surfaceContainerHighest = Color(0xFF343A31), outline = Color(0xFF8A9285), outlineVariant = Color(0xFF3F453B)
    ) else lightColorScheme(
        primary = primary, onPrimary = Color.White, primaryContainer = container, onPrimaryContainer = primary,
        secondary = primary, secondaryContainer = Color(0xFFE8ECE2), onSecondaryContainer = Color(0xFF333D30),
        background = Color(0xFFF8F7F2), onBackground = Color(0xFF22251F), surface = Color(0xFFFFFEFA),
        onSurface = Color(0xFF22251F), surfaceVariant = Color(0xFFEDEFE7), onSurfaceVariant = Color(0xFF5B6456),
        surfaceContainerLowest = Color(0xFFFFFEFA), surfaceContainerLow = Color(0xFFF2F2EA),
        surfaceContainer = Color(0xFFEDEEE6), surfaceContainerHigh = Color(0xFFE7EAE0),
        surfaceContainerHighest = Color(0xFFE1E4D9), outline = Color(0xFF777F72), outlineVariant = Color(0xFFD7DBD0)
    )
}

internal val neuraTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontFamily = FontFamily.Serif, fontSize = 39.sp, lineHeight = 46.sp),
        headlineMedium = headlineMedium.copy(fontFamily = FontFamily.Serif),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = (-.35).sp),
        bodyLarge = bodyLarge.copy(lineHeight = 25.sp, letterSpacing = .12.sp)
    )
}

@Composable
internal fun ScreenHeader(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
        Text(title, Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.titleLarge)
        actions()
    }
}

@Composable
internal fun SearchField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    TextField(
        value, onChange, modifier, singleLine = true, shape = RoundedCornerShape(16.dp),
        leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(20.dp)) },
        trailingIcon = if (value.isBlank()) null else { { IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, "Очистить поиск") } } },
        placeholder = { Text(hint) },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
        )
    )
}
