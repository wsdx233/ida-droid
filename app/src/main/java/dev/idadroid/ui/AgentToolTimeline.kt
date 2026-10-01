package dev.idadroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.idadroid.agent.ChatMessage
import dev.idadroid.agent.pretty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.max

private val UiJson = Json { prettyPrint = true; ignoreUnknownKeys = true; explicitNulls = false }
private const val TOOL_CODE_COLLAPSED_CHARS = 12_000
private fun ColorScheme.isDarkLike(): Boolean = background.luminance() < 0.5f || surface.luminance() < 0.5f
@Composable
private fun CodeBlock(
    title: String,
    code: String,
    collapsedChars: Int = TOOL_CODE_COLLAPSED_CHARS,
    maxCollapsedLines: Int = 120,
    highlight: Boolean = true
) {
    val clipboard = LocalClipboardManager.current
    val bg = MaterialTheme.colorScheme.surfaceContainerHighest
    val headerBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val lines = remember(code) { code.replace("\r\n", "\n").split("\n") }
    val lineClipped = lines.size > maxCollapsedLines
    val charClipped = code.length > collapsedChars
    val clipped = lineClipped || charClipped
    var expanded by rememberSaveable(code) { mutableStateOf(!clipped) }
    val collapsedText = remember(code, collapsedChars, maxCollapsedLines) {
        val lineLimited = if (lines.size > maxCollapsedLines) lines.take(maxCollapsedLines).joinToString("\n") else code
        if (lineLimited.length > collapsedChars) lineLimited.take(collapsedChars) else lineLimited
    }
    val displayText = if (clipped && !expanded) collapsedText else code
    val dark = MaterialTheme.colorScheme.isDarkLike()
    val highlighted = remember(displayText, title, dark, highlight) { if (highlight) fallbackHighlightCode(displayText, title, dark) else AnnotatedString(displayText) }
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = bg), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().background(headerBg).padding(horizontal = 10.dp, vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (clipped) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.size(13.dp)); Text(if (expanded) "收起" else "展开全部", fontSize = 12.sp) }
            TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(13.dp)); Text("复制", fontSize = 12.sp) }
        }
        Text(highlighted, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().background(bg).padding(10.dp), lineHeight = 18.sp)
        if (clipped && !expanded) {
            val hiddenLines = (lines.size - maxCollapsedLines).coerceAtLeast(0)
            val hiddenChars = (code.length - displayText.length).coerceAtLeast(0)
            Text(
                "… ${listOfNotNull(hiddenLines.takeIf { it > 0 }?.let { "折叠 $it 行，共 ${lines.size} 行" }, hiddenChars.takeIf { it > 0 }?.let { "隐藏 $it 个字符" }).joinToString("；")}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().background(bg).padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
            )
        }
    }
}

private fun fallbackHighlightCode(code: String, language: String, dark: Boolean): AnnotatedString = buildAnnotatedString {
    val keywords = setOf("fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while", "return", "import", "package", "const", "let", "function", "export", "from", "def", "async", "await", "try", "catch", "finally", "true", "false", "null", "None", "public", "private", "suspend", "data", "echo", "cd", "ls", "grep", "find", "cat", "sed", "awk")
    val token = Regex("(//.*|#.*|\\\"(?:\\\\.|[^\\\"])*\\\"|'(?:\\\\.|[^'])*'|\\b\\d+(?:\\.\\d+)?\\b|\\b[A-Za-z_][A-Za-z0-9_]*\\b)")
    var last = 0
    for (m in token.findAll(code)) {
        append(code.substring(last, m.range.first))
        val s = m.value
        val color = when {
            s.startsWith("//") || (s.startsWith("#") && !language.contains("json", true)) -> Color(0xFF8B949E)
            s.startsWith("\"") || s.startsWith("'") -> Color(0xFFA5D6FF)
            s.firstOrNull()?.isDigit() == true -> Color(0xFFFFD580)
            s in keywords -> Color(0xFFD0BCFF)
            else -> Color.Unspecified
        }
        withStyle(SpanStyle(color = color, fontWeight = if (s in keywords) FontWeight.Bold else FontWeight.Normal)) { append(s) }
        last = m.range.last + 1
    }
    append(code.substring(last))
}

private enum class ToolKind { Read, Edit, Write, Bash, Ls, Grep, Find, Unknown }

@Composable
internal fun ToolMessageCard(message: ChatMessage, autoOpen: Boolean, autoCollapse: Boolean) {
    val status = message.toolStatus ?: "pending"
    val kind = toolKindForName(message.toolName)
    val accent = toolKindAccent(kind)
    val border = when (status) {
        "running" -> accent.copy(alpha = .50f)
        "error" -> MaterialTheme.colorScheme.error.copy(alpha = .55f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    var open by rememberSaveable(message.id) { mutableStateOf(autoOpen) }
    var openedByAuto by rememberSaveable(message.id) { mutableStateOf(autoOpen) }
    LaunchedEffect(autoOpen, autoCollapse, message.id) {
        when {
            autoOpen -> { open = true; openedByAuto = true }
            autoCollapse && openedByAuto -> { open = false; openedByAuto = false }
        }
    }
    OutlinedCard(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, border)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open; openedByAuto = false }.padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                ToolKindIcon(kind, status)
                Text(
                    toolLabel(kind, message.toolName),
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.widthIn(max = 92.dp)
                )
                ToolOverview(message, modifier = Modifier.weight(1f))
                ToolStatusBadge(status)
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            if (open) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .72f))
                ToolPreview(message)
            }
        }
    }
}

@Composable
private fun ToolKindIcon(kind: ToolKind, status: String) {
    val accent = toolKindAccent(kind)
    Surface(shape = RoundedCornerShape(9.dp), color = accent.copy(alpha = .13f), contentColor = accent, modifier = Modifier.size(26.dp)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(toolIcon(kind), contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun toolKindAccent(kind: ToolKind): Color = when (kind) {
    ToolKind.Read, ToolKind.Edit -> MaterialTheme.colorScheme.primary
    ToolKind.Write -> MaterialTheme.colorScheme.tertiary
    ToolKind.Bash -> MaterialTheme.colorScheme.secondary
    ToolKind.Ls, ToolKind.Grep, ToolKind.Find -> MaterialTheme.colorScheme.primary
    ToolKind.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun toolIcon(kind: ToolKind): ImageVector = when (kind) {
    ToolKind.Read -> Icons.Rounded.Visibility
    ToolKind.Edit -> Icons.Rounded.Edit
    ToolKind.Write -> Icons.AutoMirrored.Rounded.NoteAdd
    ToolKind.Bash -> Icons.Rounded.Terminal
    ToolKind.Ls -> Icons.Rounded.Folder
    ToolKind.Grep -> Icons.Rounded.Search
    ToolKind.Find -> Icons.Rounded.Search
    ToolKind.Unknown -> Icons.Rounded.Build
}

@Composable
private fun toolLabel(kind: ToolKind, name: String?): String = when (kind) {
    ToolKind.Read -> "读取文件"
    ToolKind.Edit -> "编辑文件"
    ToolKind.Write -> "写入文件"
    ToolKind.Bash -> "执行命令"
    ToolKind.Ls -> "列出目录"
    ToolKind.Grep -> "搜索文本"
    ToolKind.Find -> "查找文件"
    ToolKind.Unknown -> name?.takeIf { it.isNotBlank() } ?: "tool"
}

@Composable
private fun ToolStatusBadge(status: String) {
    val color = when (status) {
        "running" -> MaterialTheme.colorScheme.primary
        "error" -> MaterialTheme.colorScheme.error
        "done" -> Color(0xFF16A34A)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
    }
}

private fun toolKindForName(name: String?): ToolKind {
    val raw = name.orEmpty().trim().lowercase()
    val last = raw.split(Regex("[./:]")).filter { it.isNotBlank() }.lastOrNull() ?: raw
    val keys = listOf(raw, last).map { it.replace(Regex("[\\s_-]"), "") }
    fun has(vararg values: String): Boolean = keys.any { key -> values.any { it == key } }
    return when {
        has("read", "readfile", "fileread", "view") -> ToolKind.Read
        has("edit", "editfile", "fileedit", "replace", "strreplace") -> ToolKind.Edit
        has("write", "writefile", "filewrite", "create", "createfile") -> ToolKind.Write
        has("bash", "shell", "terminal", "runcommand", "exec", "execute") -> ToolKind.Bash
        has("ls", "list", "listdir", "listdirectory") -> ToolKind.Ls
        has("grep", "rg", "ripgrep", "search", "searchtext") -> ToolKind.Grep
        has("find", "findfile", "findfiles", "glob") -> ToolKind.Find
        else -> ToolKind.Unknown
    }
}

@Composable
private fun ToolOverview(message: ChatMessage, modifier: Modifier = Modifier) {
    val kind = toolKindForName(message.toolName)
    val args = message.toolArgs.toolObjectOrNull()
    val result = message.toolResult.orEmpty()
    val path = args.toolPath()
    Row(modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        when (kind) {
            ToolKind.Edit -> {
                val edits = editDiffInputs(args, path)
                val stats = editChangeStats(edits)
                val paths = uniqueStrings(listOf(path) + edits.map { it.path })
                if (paths.isNotEmpty()) ToolMuted("${paths.size} files")
                if (edits.isNotEmpty()) ToolChangeStats(stats.added, stats.removed) else ToolMuted(previewText(result) ?: "等待 diff")
                paths.firstOrNull()?.let { ToolFileRef(it) }
                if (paths.size > 1) ToolMuted("+${paths.size - 1}")
                if (paths.isEmpty() && edits.isEmpty() && result.isBlank()) ToolMuted("点击查看详情")
            }
            ToolKind.Read -> {
                if (path != null) ToolFileRef(path) else ToolMuted("file")
                readLineSummary(args, result)?.takeIf { it.isNotBlank() }?.let { ToolMuted(it) }
            }
            ToolKind.Write -> {
                val content = args.firstString("content", "newText", "new_text", "replacement", "text", "value", "data")
                val added = content?.let { splitTextLines(it).size } ?: lineCount(result)
                if (added > 0) ToolChangeStats(added, 0)
                if (path != null) ToolFileRef(path) else ToolMuted(previewText(result) ?: "file")
            }
            ToolKind.Bash -> {
                val command = bashCommand(message.toolArgs)
                if (command != null) ToolInlineCode("$ ${previewText(command, 180)}") else ToolMuted(previewText(result) ?: "shell")
            }
            ToolKind.Ls -> if (path != null) ToolFileRef(path) else ToolMuted("当前目录")
            ToolKind.Grep, ToolKind.Find -> {
                args.firstString("pattern", "query", "regex", "name", "value")?.let { ToolInlineCode(previewText(it, 80) ?: it) }
                path?.let { ToolFileRef(it) }
                if (path == null && args.firstString("pattern", "query", "regex", "name", "value") == null) ToolMuted(previewText(message.toolArgs?.let { pretty(it) } ?: result) ?: "点击查看详情")
            }
            ToolKind.Unknown -> ToolMuted(compactText(path?.let { fileNameFromPath(it) }, previewText(message.toolArgs?.let { pretty(it) }), previewText(result)))
        }
    }
}

@Composable
private fun ToolMuted(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ToolInlineCode(text: String) {
    Surface(shape = RoundedCornerShape(7.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .10f), contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Text(fallbackHighlightCode(text, "bash", MaterialTheme.colorScheme.isDarkLike()), fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 6.dp, vertical = 3.dp))
    }
}

@Composable
private fun ToolChangeStats(added: Int, removed: Int) {
    val add = added.coerceAtLeast(0)
    val del = removed.coerceAtLeast(0)
    if (add == 0 && del == 0) { ToolMuted("无行变更"); return }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (add > 0) Text("+$add", color = Color(0xFF16A34A), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 12.sp, maxLines = 1)
        if (del > 0) Text("-$del", color = MaterialTheme.colorScheme.error, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun ToolFileRef(path: String) {
    val name = fileNameFromPath(path)
    Row(Modifier.widthIn(max = 190.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        ToolFileIcon(path)
        Text(name, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .88f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ToolPathPill(path: String?, fallback: String = "file") {
    val label = path?.let { fileNameFromPath(it) } ?: fallback
    Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .10f), contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.widthIn(max = 220.dp).padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            ToolFileIcon(path ?: label)
            Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ToolFileIcon(path: String) {
    val icon = when (fileExtension(path)) {
        "sh", "bash", "zsh", "fish" -> Icons.Rounded.Terminal
        "md", "txt", "log" -> Icons.Rounded.Description
        "json", "yml", "yaml", "xml", "toml" -> Icons.Rounded.Code
        "kt", "kts", "java", "js", "ts", "tsx", "py", "c", "cpp", "h", "go", "rs" -> Icons.Rounded.Code
        else -> Icons.Rounded.Description
    }
    Surface(shape = RoundedCornerShape(7.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp)) }
    }
}

@Composable
private fun ToolPreview(message: ChatMessage) {
    val kind = toolKindForName(message.toolName)
    val args = message.toolArgs.toolObjectOrNull()
    val result = message.toolResult.orEmpty()
    val path = args.toolPath()
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        when (kind) {
            ToolKind.Write -> {
                val content = args.firstString("content", "newText", "new_text", "replacement", "text", "value", "data")
                ToolPreviewHeader("写入 diff", path)
                if (!content.isNullOrBlank()) DiffBlock("Unified diff", buildWriteDiff(content, path))
                else if (result.isNotBlank()) ToolCodeBlock("输出预览", result)
                else EmptyToolPreview("没有可显示的写入内容")
                if (result.isNotBlank() && content != null) SmallToolDetails("工具结果") { ToolCodeBlock("输出预览", result, maxLines = 10) }
            }
            ToolKind.Edit -> {
                val edits = editDiffInputs(args, path)
                val diffs = buildEditDiffs(args, path)
                ToolPreviewHeader("编辑 diff", path ?: edits.firstOrNull()?.path, if (edits.size > 1) "${edits.size} blocks" else null)
                if (diffs.isNotEmpty()) DiffBlock("Unified diff", diffs)
                else if (result.isNotBlank()) ToolCodeBlock("输出预览", result)
                else EmptyToolPreview("没有可显示的编辑内容")
                if (result.isNotBlank() && diffs.isNotEmpty()) SmallToolDetails("工具结果") { ToolCodeBlock("输出预览", result, maxLines = 10) }
                else if (message.toolArgs != null && diffs.isEmpty()) SmallToolDetails("参数") { ToolCodeBlock("json", pretty(message.toolArgs), maxLines = 10) }
            }
            ToolKind.Read -> {
                val display = result.ifBlank { message.toolArgs?.let { pretty(it) }.orEmpty() }
                ToolPreviewHeader("读取", path, readLineSummary(args, result))
                if (display.isNotBlank()) { ToolCodeBlock(languageForPath(path).ifBlank { "text" }, display); ToolMetaNotice(message.toolResultMeta, result.ifBlank { display }) }
                else EmptyToolPreview("没有可显示的读取结果")
            }
            ToolKind.Bash -> {
                val command = bashCommand(message.toolArgs)
                if (!command.isNullOrBlank()) {
                    ToolPreviewHeader("执行命令", null)
                    ToolCodeBlock("$ command", command, maxLines = 24)
                }
                if (result.isNotBlank()) {
                    ToolPreviewHeader("输出预览", null)
                    ToolCodeBlock("terminal", result)
                    ToolMetaNotice(message.toolResultMeta, result)
                }
                if (command.isNullOrBlank() && result.isBlank()) EmptyToolPreview("暂无输出")
            }
            ToolKind.Ls, ToolKind.Grep, ToolKind.Find, ToolKind.Unknown -> {
                if (message.toolArgs != null) {
                    ToolPreviewHeader("参数", path)
                    ToolCodeBlock("json", pretty(message.toolArgs), maxLines = 10)
                }
                if (result.isNotBlank()) {
                    ToolPreviewHeader("输出预览", path)
                    ToolCodeBlock("输出", result)
                    ToolMetaNotice(message.toolResultMeta, result)
                }
                if (message.toolArgs == null && result.isBlank()) EmptyToolPreview("没有可显示的工具内容")
            }
        }
    }
}

@Composable
private fun ToolPreviewHeader(title: String, path: String?, extra: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Black, fontSize = 12.sp, maxLines = 1)
        path?.let { ToolPathPill(it) }
        extra?.takeIf { it.isNotBlank() }?.let { ToolMuted(it) }
    }
}

@Composable
private fun ToolCodeBlock(title: String, text: String, maxLines: Int = 18) {
    CodeBlock(title, text, maxCollapsedLines = maxLines, highlight = false)
}

@Composable
private fun ToolMetaNotice(meta: dev.idadroid.agent.ToolResultMeta?, text: String) {
    val enriched = remember(meta, text) { enrichToolMeta(meta, text) } ?: return
    val parts = buildList {
        enriched.label?.takeIf { it.isNotBlank() }?.let { add(it) }
        val shown = enriched.shownLines
        val total = enriched.totalLines
        val omitted = enriched.omittedLines
        if (shown != null && total != null && total != shown) add("显示 $shown/$total 行") else if (total != null) add("共 $total 行")
        if (omitted != null) add("省略 $omitted 行")
        val shownBytes = enriched.shownBytes
        val totalBytes = enriched.totalBytes
        if (shownBytes != null && totalBytes != null && totalBytes != shownBytes) add("${formatBytes(shownBytes)}/${formatBytes(totalBytes)}") else if (totalBytes != null) add(formatBytes(totalBytes))
    }
    if (parts.isEmpty() && enriched.truncated != true) return
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f), contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Text(listOfNotNull(if (enriched.truncated == true) "工具输出已截断" else null, parts.joinToString(" · ").takeIf { it.isNotBlank() }).joinToString(" · "), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
    }
}

private fun enrichToolMeta(meta: dev.idadroid.agent.ToolResultMeta?, text: String): dev.idadroid.agent.ToolResultMeta? {
    val lines = lineCount(text).toLong()
    var next = meta ?: return null
    if (lines > 0 && next.shownLines == null) next = next.copy(shownLines = lines)
    if (lines > 0 && next.totalLines == null && next.truncated != true) next = next.copy(totalLines = lines)
    if (next.omittedLines != null && next.shownLines != null && next.totalLines == null) next = next.copy(totalLines = next.shownLines + next.omittedLines)
    if (next.omittedLines == null && next.totalLines != null && next.shownLines != null && next.totalLines > next.shownLines) next = next.copy(omittedLines = next.totalLines - next.shownLines)
    return next
}

@Composable
private fun EmptyToolPreview(text: String) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = .55f)) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun SmallToolDetails(title: String, content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
            if (open) Box(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) { content() }
        }
    }
}

private data class EditDiffInput(val oldText: String, val newText: String, val path: String?)
private data class ChangeStats(val added: Int, val removed: Int)
private data class DiffLine(val type: DiffLineType, val text: String)
private enum class DiffLineType { Add, Del, Meta, Ctx }

@Composable
private fun DiffBlock(title: String, lines: List<DiffLine>, maxLines: Int = 240) {
    val clipboard = LocalClipboardManager.current
    val bg = MaterialTheme.colorScheme.surfaceContainerHighest
    val headerBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val clipped = lines.size > maxLines
    var expanded by rememberSaveable { mutableStateOf(false) }
    val visible = if (clipped && !expanded) lines.take(maxLines) else lines
    val text = lines.joinToString("\n") { line ->
        when (line.type) { DiffLineType.Add -> "+${line.text}"; DiffLineType.Del -> "-${line.text}"; DiffLineType.Meta -> line.text; DiffLineType.Ctx -> " ${line.text}" }
    }
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = bg), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().background(headerBg).padding(horizontal = 10.dp, vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (clipped) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.size(13.dp)); Text(if (expanded) "收起" else "展开全部", fontSize = 12.sp) }
            TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(13.dp)); Text("复制", fontSize = 12.sp) }
        }
        Column(Modifier.fillMaxWidth().background(bg)) {
            visible.forEach { line -> DiffLineRow(line) }
            if (clipped && !expanded) DiffLineRow(DiffLine(DiffLineType.Meta, "… 已隐藏 ${lines.size - maxLines} 行，共 ${lines.size} 行"))
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffLine) {
    val colors = MaterialTheme.colorScheme
    val add = Color(0xFF16A34A)
    val del = colors.error
    val bg = when (line.type) { DiffLineType.Add -> add.copy(alpha = .12f); DiffLineType.Del -> del.copy(alpha = .12f); DiffLineType.Meta -> colors.primaryContainer.copy(alpha = .36f); DiffLineType.Ctx -> Color.Transparent }
    val stripe = when (line.type) { DiffLineType.Add -> add; DiffLineType.Del -> del; else -> Color.Transparent }
    val signColor = when (line.type) { DiffLineType.Add -> add; DiffLineType.Del -> del; DiffLineType.Meta -> colors.primary; DiffLineType.Ctx -> colors.onSurfaceVariant }
    val textColor = when (line.type) { DiffLineType.Meta -> colors.primary; else -> colors.onSurface }
    Row(Modifier.fillMaxWidth().background(bg), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(3.dp).height(20.dp).background(stripe))
        Text(when (line.type) { DiffLineType.Add -> "+"; DiffLineType.Del -> "-"; DiffLineType.Meta -> ""; DiffLineType.Ctx -> " " }, color = signColor, fontFamily = FontFamily.Monospace, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.width(28.dp).padding(top = 2.dp), maxLines = 1)
        Text(line.text.ifEmpty { " " }, color = textColor, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f).padding(vertical = 2.dp, horizontal = 4.dp))
    }
}

private fun buildWriteDiff(content: String, filePath: String?): List<DiffLine> {
    val lines = splitTextLines(content)
    return listOf(DiffLine(DiffLineType.Meta, "+++ ${filePath ?: "file"}"), DiffLine(DiffLineType.Meta, "@@ -0,0 +1,${lines.size} @@")) + lines.map { DiffLine(DiffLineType.Add, it) }
}

private fun buildEditDiffs(args: JsonObject?, filePath: String?): List<DiffLine> {
    val edits = editDiffInputs(args, filePath)
    return edits.flatMapIndexed { index, edit ->
        val lines = if (edit.oldText.isNotBlank()) buildUnifiedDiff(edit.oldText, edit.newText, edit.path ?: filePath) else buildWriteDiff(edit.newText, edit.path ?: filePath)
        if (index == 0) lines else listOf(DiffLine(DiffLineType.Meta, "")) + lines
    }
}

private fun editDiffInputs(args: JsonObject?, fallbackPath: String?): List<EditDiffInput> {
    if (args == null) return emptyList()
    val out = mutableListOf<EditDiffInput>()
    fun push(obj: JsonObject?) {
        if (obj == null) return
        val oldText = obj.firstString("oldText", "old_text", "old", "original", "before").orEmpty()
        val newText = obj.firstString("newText", "new_text", "replacement", "replace", "new", "after", "content", "text", "value").orEmpty()
        val path = obj.toolPath() ?: fallbackPath
        if (oldText.isNotBlank() || newText.isNotBlank()) out += EditDiffInput(oldText, newText, path)
    }
    listOf("edits", "changes", "replacements").forEach { key -> (args[key] as? JsonArray)?.forEach { push(it.toolObjectOrNull()) } }
    push(args)
    return out.distinctBy { Triple(it.oldText, it.newText, it.path) }
}

private fun editChangeStats(edits: List<EditDiffInput>): ChangeStats = edits.fold(ChangeStats(0, 0)) { acc, edit ->
    val changed = changedLineCounts(edit.oldText, edit.newText)
    ChangeStats(acc.added + changed.added, acc.removed + changed.removed)
}

private fun changedLineCounts(oldText: String, newText: String): ChangeStats {
    if (oldText.isBlank() && newText.isBlank()) return ChangeStats(0, 0)
    val oldLines = splitTextLines(oldText)
    val newLines = splitTextLines(newText)
    if (oldLines.isEmpty()) return ChangeStats(newLines.size, 0)
    if (newLines.isEmpty()) return ChangeStats(0, oldLines.size)
    if (oldLines.size * newLines.size > 90_000) return ChangeStats(newLines.size, oldLines.size)
    val dp = Array(oldLines.size + 1) { IntArray(newLines.size + 1) }
    for (i in oldLines.size - 1 downTo 0) for (j in newLines.size - 1 downTo 0) dp[i][j] = if (oldLines[i] == newLines[j]) dp[i + 1][j + 1] + 1 else max(dp[i + 1][j], dp[i][j + 1])
    val common = dp[0][0]
    return ChangeStats(newLines.size - common, oldLines.size - common)
}

private fun buildUnifiedDiff(oldText: String, newText: String, filePath: String?): List<DiffLine> {
    val oldLines = splitTextLines(oldText)
    val newLines = splitTextLines(newText)
    val header = listOf(DiffLine(DiffLineType.Meta, "--- ${filePath ?: "file"}"), DiffLine(DiffLineType.Meta, "+++ ${filePath ?: "file"}"), DiffLine(DiffLineType.Meta, "@@ -1,${oldLines.size} +1,${newLines.size} @@"))
    if (oldLines.size * newLines.size > 90_000) return header + oldLines.map { DiffLine(DiffLineType.Del, it) } + newLines.map { DiffLine(DiffLineType.Add, it) }
    val dp = Array(oldLines.size + 1) { IntArray(newLines.size + 1) }
    for (i in oldLines.size - 1 downTo 0) for (j in newLines.size - 1 downTo 0) dp[i][j] = if (oldLines[i] == newLines[j]) dp[i + 1][j + 1] + 1 else max(dp[i + 1][j], dp[i][j + 1])
    val body = mutableListOf<DiffLine>()
    var i = 0
    var j = 0
    while (i < oldLines.size || j < newLines.size) {
        when {
            i < oldLines.size && j < newLines.size && oldLines[i] == newLines[j] -> { body += DiffLine(DiffLineType.Ctx, oldLines[i]); i++; j++ }
            j >= newLines.size || (i < oldLines.size && dp[i + 1][j] >= dp[i][j + 1]) -> { body += DiffLine(DiffLineType.Del, oldLines[i]); i++ }
            else -> { body += DiffLine(DiffLineType.Add, newLines[j]); j++ }
        }
    }
    return header + body
}

private fun splitTextLines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.replace("\r\n", "\n").split("\n")
private fun lineCount(text: String): Int = if (text.isBlank()) 0 else splitTextLines(text).size
private fun JsonObject?.toolPath(): String? = this?.firstString("path", "file", "filename", "filePath", "file_path", "directory", "dir", "cwd")
private fun JsonObject?.firstString(vararg keys: String): String? = keys.firstNotNullOfOrNull { this?.stringValue(it)?.takeIf(String::isNotBlank) }
private fun uniqueStrings(values: List<String?>): List<String> = values.mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }.distinct()
private fun fileNameFromPath(path: String): String = path.replace('\\', '/').trimEnd('/').split('/').filter { it.isNotBlank() }.lastOrNull() ?: path.ifBlank { "file" }
private fun fileExtension(name: String): String = fileNameFromPath(name).lowercase().substringAfterLast('.', missingDelimiterValue = "").takeIf { it != fileNameFromPath(name).lowercase() }.orEmpty()
private fun languageForPath(path: String?): String {
    val value = path?.substringBefore('?')?.substringBefore('#')?.trim().orEmpty()
    val ext = value.substringAfterLast('/', value).substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when (ext) {
        "kt", "kts" -> "kotlin"; "java" -> "java"; "js", "jsx", "mjs", "cjs", "ts", "tsx" -> "javascript"; "json" -> "json"; "py" -> "python"; "html", "htm", "xml", "svg", "vue" -> "markup"; "css", "scss", "sass" -> "css"; "md", "markdown" -> "markdown"; "yaml", "yml" -> "yaml"; "c", "h" -> "c"; "cpp", "cc", "cxx", "hpp", "hh" -> "cpp"; "sh", "bash", "zsh", "fish" -> "bash"; else -> if (value.indexOf('.') < 0 && value.indexOf('/') < 0) value.lowercase() else ""
    }
}
private fun readLineSummary(args: JsonObject?, result: String): String? {
    val start = args.numericValue("offset", "start", "startLine", "start_line", "line", "from")
    val limit = args.numericValue("limit", "lines", "lineCount", "line_count", "count")
    return when {
        start != null && limit != null -> "L$start-${max(start, start + limit - 1)}"
        start != null -> "L$start+"
        limit != null -> "first $limit lines"
        result.isNotBlank() -> lineCount(result).takeIf { it > 0 }?.let { if (it == 1) "L1" else "L1-$it" }
        else -> null
    }
}
private fun JsonObject?.numericValue(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key -> this?.stringValue(key)?.replace(",", "")?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }?.toInt()?.coerceAtLeast(1) }
private fun compactText(vararg parts: String?): String = parts.mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }.joinToString(" · ").ifBlank { "Details" }
private fun previewText(value: String?, max: Int = 90): String? {
    val text = value?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    if (text.isBlank()) return null
    return if (text.length > max) text.take(max - 1) + "…" else text
}
private fun bashCommand(value: JsonElement?): String? {
    val obj = value.toolObjectOrNull()
    return obj?.stringValue("command") ?: obj?.stringValue("cmd") ?: obj?.stringValue("script") ?: obj?.stringValue("value") ?: value.primitiveString()
}
private fun JsonElement?.toolObjectOrNull(): JsonObject? = when (this) {
    is JsonObject -> this
    is JsonPrimitive -> contentOrNull?.let { runCatching { UiJson.parseToJsonElement(it).jsonObject }.getOrNull() }
    else -> null
}
private fun JsonElement?.primitiveString(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonObject.stringValue(key: String): String? = when (val value = this[key]) { is JsonPrimitive -> value.contentOrNull; null -> null; else -> pretty(value) }
