package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.rikkahub.data.ai.tools.routing.ToolDomain
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.theme.CustomColors

@Composable
fun SettingDomainPage(
    settings: Settings,
    vm: SettingVM,
    onBack: () -> Unit,
) {
    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            TopAppBar(
                title = { Text("域分类管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = null)
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "域触发描述",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "AI 在路由表中看到的域描述。修改后影响 use_domain 的触发准确率。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(ToolDomain.entries.toList()) { domain ->
                val customDesc = settings.customDomainDescriptions[domain.label]
                val currentDesc = customDesc ?: domain.triggerDescription
                val isCustom = customDesc != null

                DomainDescriptionCard(
                    domainLabel = domain.label,
                    description = currentDesc,
                    isCustom = isCustom,
                    onEdit = { newDesc ->
                        val updated = settings.customDomainDescriptions.toMutableMap()
                        updated[domain.label] = newDesc
                        vm.updateSettings(settings.copy(customDomainDescriptions = updated))
                    },
                    onReset = {
                        val updated = settings.customDomainDescriptions.toMutableMap()
                        updated.remove(domain.label)
                        vm.updateSettings(settings.copy(customDomainDescriptions = updated))
                    },
                )
            }

            if (settings.toolDomainOverrides.isNotEmpty()) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        "手动覆盖",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "以下工具被手动指定了域，优先于自动分类。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                items(settings.toolDomainOverrides.entries.toList()) { (toolName, domain) ->
                    ToolOverrideCard(
                        toolName = toolName,
                        domain = domain,
                        onRemove = {
                            val updated = settings.toolDomainOverrides.toMutableMap()
                            updated.remove(toolName)
                            vm.updateSettings(settings.copy(toolDomainOverrides = updated))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DomainDescriptionCard(
    domainLabel: String,
    description: String,
    isCustom: Boolean,
    onEdit: (String) -> Unit,
    onReset: () -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    var editText by remember(description) { mutableStateOf(description) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true }
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "[$domainLabel]",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isCustom) {
                    TextButton(onClick = onReset) {
                        Text("重置", style = MaterialTheme.typography.labelSmall)
                    }
                }
                IconButton(onClick = { showDialog = true }, modifier = Modifier.size(24.dp)) {
                    Icon(HugeIcons.Edit01, contentDescription = null, modifier = Modifier.size(14.dp))
                }
            }
        }
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = if (isCustom) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("编辑 [$domainLabel] 触发描述") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        label = { Text("触发描述") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 3,
                    )
                    Text(
                        "AI 会看到: $domainLabel - $editText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onEdit(editText)
                    showDialog = false
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun ToolOverrideCard(
    toolName: String,
    domain: String,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(toolName, style = MaterialTheme.typography.bodyMedium)
            Text(
                "-> [$domain]",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
            Icon(HugeIcons.Delete01, contentDescription = "Remove", modifier = Modifier.size(14.dp))
        }
    }
}
