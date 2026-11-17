package com.neura.app

import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val codeBackground = Color(0xFF151B27)
private val codeForeground = Color(0xFFDCE4F1)
private val codePurple = Color(0xFFC4A7FF)
private val codeGreen = Color(0xFF91D9B5)
private val codeOrange = Color(0xFFF2B880)

@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val lines = remember(markdown) { markdown.replace("\r\n", "\n").split("\n") }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            when {
                line.trimStart().startsWith("```") -> {
                    val language = line.trim().removePrefix("```").trim().ifBlank { "code" }
                    val code = mutableListOf<String>(); index++
                    while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                        code.add(lines[index]); index++
                    }
                    CodeCard(language, code.joinToString("\n"))
                }
                line.matches(Regex("^#{1,6}\\s+.*")) -> {
                    val level = line.takeWhile { it == '#' }.length
                    Text(inlineMarkdown(line.drop(level).trim(), uriHandler::openUri),
                        style = when (level) { 1 -> MaterialTheme.typography.headlineMedium; 2 -> MaterialTheme.typography.headlineSmall; else -> MaterialTheme.typography.titleMedium },
                        fontWeight = FontWeight.Bold)
                }
                line.trim() == "---" || line.trim() == "***" -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                line.startsWith("> ") -> Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f),
                    shape = RoundedCornerShape(8.dp)) {
                    Text(inlineMarkdown(line.drop(2), uriHandler::openUri), modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
                line.matches(Regex("^\\s*([-*+] |\\d+\\. ).+")) -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("•", color = MaterialTheme.colorScheme.primary)
                        Text(inlineMarkdown(line.trimStart().replaceFirst(Regex("^([-*+] |\\d+\\. )"), ""), uriHandler::openUri),
                            style = MaterialTheme.typography.bodyLarge)
                    }
                }
                line.contains('|') && index + 1 < lines.size && lines[index + 1].matches(Regex("^[| :\\-]+$")) -> {
                    val table = mutableListOf(line)
                    index += 2
                    while (index < lines.size && lines[index].contains('|') && lines[index].isNotBlank()) {
                        table.add(lines[index]); index++
                    }
                    SimpleMarkdownTable(table)
                    continue
                }
                line.isNotBlank() -> Text(inlineMarkdown(line, uriHandler::openUri), style = MaterialTheme.typography.bodyLarge, lineHeight = 25.sp)
                else -> Spacer(Modifier.height(3.dp))
            }
            index++
        }
    }
}

@Composable
private fun SimpleMarkdownTable(rows: List<String>) {
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        rows.forEachIndexed { index, row ->
            val cells = row.trim('|').split('|')
            Row(Modifier.background(if (index == 0) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)) {
                cells.forEach { cell ->
                    Text(cell.trim(), Modifier.widthIn(min = 110.dp, max = 220.dp).padding(10.dp),
                        fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

// Inline syntax is rendered using native AnnotatedString spans; link spans are handled by Text link annotations.
private fun inlineMarkdown(value: String, openLink: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    val expression = Regex("(\\*\\*.+?\\*\\*|__.+?__|`[^`]+`|\\*[^*\\n]+\\*|\\[[^]]+\\]\\(https?://[^)]+\\))")
    var previous = 0
    expression.findAll(value).forEach { match ->
        append(value.substring(previous, match.range.first))
        val token = match.value
        when {
            token.startsWith("**") || token.startsWith("__") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.substring(2, token.length - 2)) }
            token.startsWith("`") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codePurple, background = codeBackground)) { append(token.drop(1).dropLast(1)) }
            token.startsWith("*") -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(token.drop(1).dropLast(1)) }
            token.startsWith("[") -> {
                val separator = token.indexOf("](")
                val label = token.substring(1, separator)
                val target = token.substring(separator + 2, token.length - 1)
                // LinkAnnotation.Clickable allows clicks without an additional text overlay.
                pushLink(androidx.compose.ui.text.LinkAnnotation.Clickable(tag = target,
                    styles = androidx.compose.ui.text.TextLinkStyles(style = SpanStyle(color = Color(0xFF9FB8FF), textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = { openLink(target) }))
                append(label)
                pop()
            }
            else -> append(token)
        }
        previous = match.range.last + 1
    }
    append(value.substring(previous))
}

@Composable
private fun CodeCard(language: String, code: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    Surface(color = codeBackground, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 5.dp, top = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(language.uppercase(), modifier = Modifier.padding(top = 12.dp), color = Color(0xFFACB8CB), style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { clipboard.setText(AnnotatedString(code)); copied = true }) {
                    Icon(if (copied) Icons.Default.Check else Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp)); Text(if (copied) "Скопировано" else "Копировать", fontSize = 12.sp)
                }
            }
            HorizontalDivider(color = Color(0xFF293144))
            SelectionContainer {
                Box(Modifier.horizontalScroll(rememberScrollState()).padding(14.dp)) {
                    Text(highlightCode(code, language), color = codeForeground, fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp, lineHeight = 19.sp, softWrap = false)
                }
            }
        }
    }
}

private fun highlightCode(code: String, language: String): AnnotatedString = buildAnnotatedString {
    append(code)
    // Lightweight native highlighting; unsupported languages remain readable.
    val keywords = Regex("\\b(fun|val|var|class|object|private|public|return|if|else|for|while|when|import|package|def|print|from|async|await|const|let|function|new|true|false|null|None|True|False|interface|type|export|default|try|catch|throw|suspend|override|data)\\b")
    keywords.findAll(code).forEach { addStyle(SpanStyle(color = codePurple), it.range.first, it.range.last + 1) }
    Regex("\\b\\d+(?:\\.\\d+)?\\b").findAll(code).forEach { addStyle(SpanStyle(color = codeOrange), it.range.first, it.range.last + 1) }
    Regex("\"(?:\\\\.|[^\"\\n])*\"|'(?:\\\\.|[^'\\n])*'").findAll(code).forEach { addStyle(SpanStyle(color = codeGreen), it.range.first, it.range.last + 1) }
    Regex("(?m)(//[^\\n]*|#[^\\n]*)").findAll(code).forEach { addStyle(SpanStyle(color = Color(0xFF8994A6)), it.range.first, it.range.last + 1) }
}
