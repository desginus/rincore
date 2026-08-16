package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.ecosystem.EcosystemInstruction
import me.rerere.rikkahub.ecosystem.EcosystemManager
import me.rerere.rikkahub.ecosystem.EcosystemSource
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors

@Composable
fun SettingEcosystemPage() {
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("生态系统") },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
                actions = {
                    TextButton(onClick = { EcosystemManager.refresh() }) {
                        Text("刷新")
                    }
                },
            )
        }
    ) { innerPadding ->
        EcosystemContent(innerPadding)
    }
}

/** 生态系统内容 — 供独立页与「技能与生态」合并页复用 */
@Composable
fun EcosystemContent(innerPadding: PaddingValues = PaddingValues()) {
    val instructions by EcosystemManager.instructions.collectAsStateWithLifecycle()
    val enabledIds by EcosystemManager.enabledIds.collectAsStateWithLifecycle()
    val scannedDirs by EcosystemManager.scannedDirs.collectAsStateWithLifecycle()

    if (instructions.isEmpty()) {
        if (instructions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("未发现生态系统文件", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("扫描目录:", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    scannedDirs.forEach { dir ->
                        Text(dir, style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                }
            }
        } else {
            // 按生态分组
            val grouped = instructions.groupBy { it.source }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(8.dp),
            ) {
                item {
                    Text(
                        "${instructions.size} instructions from ${grouped.size} ecosystems | ${enabledIds.size} enabled",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                // GitHub Token 配置
                item {
                    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("GitHub Token", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(4.dp))
                            var token by remember { mutableStateOf(me.rerere.rikkahub.ecosystem.EcosystemManager.getGitHubToken()) }
                            OutlinedTextField(
                                value = token,
                                onValueChange = {
                                    token = it
                                    me.rerere.rikkahub.ecosystem.EcosystemManager.setGitHubToken(it)
                                },
                                label = { Text("Personal Access Token") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "用于 github:owner/repo 格式的 clawhub_install",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                // ClawHub 代理配置 (v3.6.95: fake-ip/VPN 环境)
                item {
                    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("ClawHub 代理", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(4.dp))
                            var proxy by remember { mutableStateOf(me.rerere.rikkahub.ecosystem.EcosystemManager.getClawhubProxy()) }
                            OutlinedTextField(
                                value = proxy,
                                onValueChange = {
                                    proxy = it
                                    me.rerere.rikkahub.ecosystem.EcosystemManager.setClawhubProxy(it)
                                },
                                label = { Text("host:port（如 127.0.0.1:7890）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "fake-ip/VPN 环境 clawhub.ai 直连失败时填本地代理端口，重启后生效。留空走默认网络。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                grouped.forEach { (source, insts) ->
                    item {
                        Text(
                            "${source.displayName} (${insts.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(insts) { inst -> EcosystemItem(inst, enabledIds) }
                }
            }
        }
    }
}

@Composable
private fun EcosystemItem(inst: EcosystemInstruction, enabledIds: Set<String>) {
    val id = EcosystemManager.idOf(inst)
    val isEnabled = id in enabledIds

    val sourceColor = when (inst.source) {
        EcosystemSource.OPENCLAW -> MaterialTheme.colorScheme.primary
        EcosystemSource.CLAUDE_CODE -> MaterialTheme.colorScheme.error
        EcosystemSource.CURSOR -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp, horizontal = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = sourceColor.copy(alpha = 0.15f), shape = MaterialTheme.shapes.extraSmall) {
                        Text(
                            inst.source.displayName,
                            fontSize = 10.sp,
                            color = sourceColor,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(inst.fileName, style = MaterialTheme.typography.titleSmall)
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    inst.content.lines().firstOrNull { it.isNotBlank() && !it.startsWith("---") }
                        ?.take(100) ?: "(empty)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    "${inst.content.length}c • ${inst.role.name}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = { EcosystemManager.setEnabled(id, it) },
            )
        }
    }
}
