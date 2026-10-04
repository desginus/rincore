/* 【域 E·设置体系】 — 设置页 | 地图: docs/APP_MAP.md §E */
package me.rerere.rikkahub.ui.pages.setting

/* ───【自研】SettingMediaGenPage.kt — 媒体生成提供商配置 (v4.8.100)
 * mediagen 模块的配置面: 厂商级 (凭据 + 模型列表), 模型带 kind (图像/视频)。
 * 视频生成页消费此处的 VIDEO 模型。写链: 一次动作一次 updateSettings。
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors

private enum class MediaVendor(val label: String, val defaultBaseUrl: String) {
    OPENAI("OpenAI", "https://api.openai.com/v1"),
    ALIYUN("阿里云百炼", "https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1"),
    VOLCENGINE("火山方舟", "https://ark.cn-beijing.volces.com/api/v3"),
    MINIMAX("MiniMax", "https://api.minimaxi.com/v2"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingMediaGenPage(
    settings: Settings,
    vm: SettingVM,
    onBack: () -> Unit,
) {
    var showAddProvider by remember { mutableStateOf(false) }
    var addingModelTo by remember { mutableStateOf<MediaGenerationProviderSetting?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_page_media_gen)) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (settings.mediaGenerationProviders.isEmpty()) {
                Text(
                    stringResource(R.string.media_gen_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            settings.mediaGenerationProviders.forEach { provider ->
                Card {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(provider.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    provider.baseUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = {
                                vm.updateSettings(
                                    settings.copy(
                                        mediaGenerationProviders = settings.mediaGenerationProviders.filterNot { it.id == provider.id },
                                    ),
                                )
                            }) {
                                Icon(HugeIcons.Delete01, stringResource(R.string.common_delete))
                            }
                        }
                        Text(stringResource(R.string.media_gen_models), style = MaterialTheme.typography.labelMedium)
                        provider.models.forEach { m ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "· " + m.modelId + (if (m.displayName.isNotBlank()) " (" + m.displayName + ")" else "") + "  [" + m.kind.name + "]",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(onClick = {
                                    vm.updateSettings(
                                        settings.copy(
                                            mediaGenerationProviders = settings.mediaGenerationProviders.map { p ->
                                                if (p.id == provider.id) {
                                                    p.copyProvider(models = p.models.filterNot { it.id == m.id })
                                                } else p
                                            },
                                        ),
                                    )
                                }) {
                                    Text(stringResource(R.string.common_delete))
                                }
                            }
                        }
                        OutlinedButton(onClick = { addingModelTo = provider }) {
                            Text(stringResource(R.string.media_gen_add_model))
                        }
                    }
                }
            }
            Button(onClick = { showAddProvider = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.media_gen_add_provider))
            }
        }
    }

    if (showAddProvider) {
        AddProviderDialog(settings, vm) { showAddProvider = false }
    }
    addingModelTo?.let { provider ->
        AddModelDialog(settings, vm, provider) { addingModelTo = null }
    }
}

@Composable
private fun AddProviderDialog(
    settings: Settings,
    vm: SettingVM,
    onDismiss: () -> Unit,
) {
    var vendor by remember { mutableStateOf(MediaVendor.OPENAI) }
    var vendorExpanded by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var workspaceId by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.media_gen_add_provider)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 厂商
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(onClick = { vendorExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.media_gen_vendor) + ": " + vendor.label)
                    }
                    DropdownMenu(expanded = vendorExpanded, onDismissRequest = { vendorExpanded = false }) {
                        MediaVendor.entries.forEach { v ->
                            DropdownMenuItem(
                                text = { Text(v.label) },
                                onClick = { vendor = v; vendorExpanded = false },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.media_gen_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.media_gen_api_key)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.media_gen_base_url)) },
                    placeholder = { Text(vendor.defaultBaseUrl) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (vendor == MediaVendor.ALIYUN) {
                    OutlinedTextField(
                        value = workspaceId,
                        onValueChange = { workspaceId = it },
                        label = { Text(stringResource(R.string.media_gen_workspace_id)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val resolvedBase = baseUrl.ifBlank { vendor.defaultBaseUrl }
                val resolvedName = name.ifBlank { vendor.label }
                val created: MediaGenerationProviderSetting = when (vendor) {
                    MediaVendor.OPENAI -> MediaGenerationProviderSetting.OpenAI(
                        name = resolvedName, apiKey = apiKey, baseUrl = resolvedBase,
                    )
                    MediaVendor.ALIYUN -> MediaGenerationProviderSetting.Aliyun(
                        name = resolvedName, apiKey = apiKey, workspaceId = workspaceId, baseUrl = resolvedBase,
                    )
                    MediaVendor.VOLCENGINE -> MediaGenerationProviderSetting.Volcengine(
                        name = resolvedName, apiKey = apiKey, baseUrl = resolvedBase,
                    )
                    MediaVendor.MINIMAX -> MediaGenerationProviderSetting.MiniMax(
                        name = resolvedName, apiKey = apiKey, baseUrl = resolvedBase,
                    )
                }
                vm.updateSettings(
                    settings.copy(mediaGenerationProviders = settings.mediaGenerationProviders + created),
                )
                onDismiss()
            }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun AddModelDialog(
    settings: Settings,
    vm: SettingVM,
    provider: MediaGenerationProviderSetting,
    onDismiss: () -> Unit,
) {
    var modelId by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(MediaKind.VIDEO) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.media_gen_add_model)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text(stringResource(R.string.media_gen_model_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(stringResource(R.string.media_gen_display_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.media_gen_kind),
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    MediaKind.entries.forEach { k ->
                        FilterChip(
                            selected = kind == k,
                            onClick = { kind = k },
                            label = { Text(k.name) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (modelId.isBlank()) return@TextButton
                    val updated = provider.copyProvider(
                        models = provider.models + MediaGenerationModel(
                            modelId = modelId.trim(),
                            kind = kind,
                            displayName = displayName.trim(),
                        ),
                    )
                    vm.updateSettings(
                        settings.copy(
                            mediaGenerationProviders = settings.mediaGenerationProviders.map { p ->
                                if (p.id == provider.id) updated else p
                            },
                        ),
                    )
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
