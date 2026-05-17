package dev.idadroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.idadroid.mcp.IdaMcpLaunchSettings
import dev.idadroid.mcp.IdaMcpSessionState
import dev.idadroid.mcp.IdaMcpStatus

@Composable
fun McpFunctionPanel(
    mcpState: IdaMcpSessionState,
    onStartMcp: (IdaMcpLaunchSettings) -> Unit,
    onStopMcp: () -> Unit,
    onRestartMcp: (IdaMcpLaunchSettings) -> Unit,
    onShowMcpLog: () -> Unit
) {
    var settings by remember(mcpState.settings) { mutableStateOf(mcpState.settings) }
    val running = mcpState.status == IdaMcpStatus.Running
    val starting = mcpState.status == IdaMcpStatus.Starting
    LazyPanelColumn {
        item {
            PanelStatusCard(
                title = "IDA MCP HTTP",
                body = "${mcpState.status.name} · ${mcpState.endpoint}",
                ok = running
            )
        }
        item {
            Text(
                "使用 ida-mcp serve-http 启动 Streamable HTTP；HTTP/SSE 支持多客户端并发连接，IDA 操作会在服务端串行执行。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = settings.bindHost,
                    onValueChange = { settings = settings.copy(bindHost = it) },
                    label = { Text("Bind Host") },
                    singleLine = true,
                    enabled = !starting,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = settings.port.toString(),
                    onValueChange = { value -> settings = settings.copy(port = value.filter { it.isDigit() }.toIntOrNull() ?: settings.port) },
                    label = { Text("Port") },
                    singleLine = true,
                    enabled = !starting,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            OutlinedTextField(
                value = settings.allowOrigin,
                onValueChange = { settings = settings.copy(allowOrigin = it) },
                label = { Text("Allow Origin") },
                singleLine = true,
                enabled = !starting,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            OutlinedTextField(
                value = settings.allowHost,
                onValueChange = { settings = settings.copy(allowHost = it) },
                label = { Text("Allow Host（局域网可填 *）") },
                singleLine = true,
                enabled = !starting,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = settings.sessionKeepAliveSecs.toString(),
                    onValueChange = { value -> settings = settings.copy(sessionKeepAliveSecs = value.filter { it.isDigit() }.toIntOrNull() ?: settings.sessionKeepAliveSecs) },
                    label = { Text("Session 秒") },
                    singleLine = true,
                    enabled = !starting,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { settings = settings.copy(stateless = !settings.stateless) },
                    enabled = !starting,
                    modifier = Modifier.weight(1f).defaultMinSize(minWidth = 1.dp)
                ) { Text(if (settings.stateless) "Stateless: 开" else "Stateless: 关", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { onStartMcp(settings.sanitized()) }, enabled = !starting && !running, modifier = Modifier.weight(1f).defaultMinSize(minWidth = 1.dp)) {
                    if (starting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (starting) "启动中…" else "启动")
                }
                OutlinedButton(onClick = onStopMcp, enabled = running || starting, modifier = Modifier.weight(1f).defaultMinSize(minWidth = 1.dp)) {
                    Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("停止")
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onRestartMcp(settings.sanitized()) }, enabled = !starting, modifier = Modifier.weight(1f).defaultMinSize(minWidth = 1.dp)) { Text("重启") }
                OutlinedButton(onClick = onShowMcpLog, modifier = Modifier.weight(1f).defaultMinSize(minWidth = 1.dp)) { Text("查看日志") }
            }
        }
        mcpState.message.takeIf { it.isNotBlank() }?.let { message ->
            item { Text(message, color = if (mcpState.status == IdaMcpStatus.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

