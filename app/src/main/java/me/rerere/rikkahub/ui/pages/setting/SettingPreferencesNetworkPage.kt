package me.rerere.rikkahub.ui.pages.setting


/* ───【2.4.11 移植】SettingPreferencesNetworkPage | v3.9.12 新增
 * 来源: 原版 2.4.11 移植 (SettingPreferencesNetworkPage)
 * 功能: 网络设置 — 代理 URL / 鉴权 + 连接测试 + 强兼容模式
 * 改动: 直接移植原版, 包名一致, 标识 RinCore 用法不变
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.network.toProxyOrNull
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val PROXY_TEST_URL = "https://www.google.com/generate_204"

@Composable
fun SettingPreferencesNetworkPage(vm: SettingVM = koinViewModel()) {
    val httpClient = koinInject<OkHttpClient>()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var proxyUrl by remember(settings.networkSetting.proxyUrl) {
        mutableStateOf(settings.networkSetting.proxyUrl)
    }
    var proxyUsername by remember(settings.networkSetting.proxyUsername) {
        mutableStateOf(settings.networkSetting.proxyUsername)
    }
    var proxyPassword by remember(settings.networkSetting.proxyPassword) {
        mutableStateOf(settings.networkSetting.proxyPassword)
    }
    var proxyUrlDraft by remember { mutableStateOf("") }
    var proxyUsernameDraft by remember { mutableStateOf("") }
    var proxyPasswordDraft by remember { mutableStateOf("") }
    var proxyPasswordVisible by remember { mutableStateOf(false) }
    var proxyDialogVisible by remember { mutableStateOf(false) }
    val proxyUrlInvalid = proxyUrlDraft.isNotBlank() && proxyUrlDraft.toProxyOrNull() == null
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    var proxyTesting by remember { mutableStateOf(false) }
    // v3.9.15: 部分开启 — 模型勾选弹窗
    var modelPickerVisible by remember { mutableStateOf(false) }

    fun saveProxy() {
        vm.updateSettings(
            settings.copy(
                networkSetting = settings.networkSetting.copy(
                    proxyUrl = proxyUrlDraft,
                    proxyUsername = proxyUsernameDraft,
                    proxyPassword = proxyPasswordDraft,
                ),
            )
        )
    }

    fun resetProxy() {
        proxyUrlDraft = ""
        proxyUsernameDraft = ""
        proxyPasswordDraft = ""
    }

    fun testProxy() {
        val proxy = proxyUrl.toProxyOrNull() ?: return
        if (proxyTesting) return
        proxyTesting = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val testClient = httpClient.newBuilder()
                        .proxy(proxy)
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .callTimeout(15, TimeUnit.SECONDS)
                        .build()
                    testClient.newCall(
                        Request.Builder()
                            .url(PROXY_TEST_URL)
                            .head()
                            .build()
                    ).execute().use { response ->
                        if (response.code != 204) {
                            throw IOException("HTTP ${response.code} ${response.message}")
                        }
                    }
                }
            }
            result.onSuccess {
                toaster.show(
                    context.getString(R.string.backup_page_connection_success),
                    type = ToastType.Success,
                )
            }.onFailure { error ->
                toaster.show(
                    context.getString(
                        R.string.backup_page_connection_failed,
                        error.message.orEmpty(),
                    ),
                    type = ToastType.Error,
                )
            }
            proxyTesting = false
        }
    }

    if (proxyDialogVisible) {
        AlertDialog(
            onDismissRequest = { proxyDialogVisible = false },
            modifier = Modifier.imePadding(),
            title = {
                Text(stringResource(R.string.setting_page_preferences_network_proxy))
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = proxyUrlDraft,
                        onValueChange = { proxyUrlDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(stringResource(R.string.setting_page_preferences_network_proxy))
                        },
                        placeholder = { Text("http://127.0.0.1:7890") },
                        supportingText = {
                            Text(
                                stringResource(
                                    if (proxyUrlInvalid) {
                                        R.string.setting_page_preferences_network_proxy_invalid
                                    } else {
                                        R.string.setting_page_preferences_network_proxy_desc
                                    }
                                )
                            )
                        },
                        isError = proxyUrlInvalid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = proxyUsernameDraft,
                        onValueChange = { proxyUsernameDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.backup_page_username)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = proxyPasswordDraft,
                        onValueChange = { proxyPasswordDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.backup_page_password)) },
                        visualTransformation = if (proxyPasswordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { proxyPasswordVisible = !proxyPasswordVisible },
                            ) {
                                Icon(
                                    imageVector = if (proxyPasswordVisible) {
                                        HugeIcons.ViewOff
                                    } else {
                                        HugeIcons.View
                                    },
                                    contentDescription = null,
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                    )
                    TextButton(
                        onClick = ::resetProxy,
                        modifier = Modifier.align(Alignment.End),
                        enabled = proxyUrlDraft.isNotEmpty() ||
                            proxyUsernameDraft.isNotEmpty() ||
                            proxyPasswordDraft.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.setting_model_page_reset_to_default))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        saveProxy()
                        proxyDialogVisible = false
                    },
                    enabled = !proxyUrlInvalid,
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_preferences_network)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                // v3.16.0: 强兼容模式 — 请求体按 Cherry Studio 极简格式发送
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("强兼容模式") },
                ) {
                    item(
                        headlineContent = { Text("Cherry Studio 兼容请求格式") },
                        supportingContent = {
                            Text(
                                "开启后按 Cherry Studio 的极简格式发送请求, " +
                                    "最大化任意模型可用性。代价: 思考控制与历史推理回传停用。"
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.networkSetting.cherryCompatMode,
                                onCheckedChange = { checked ->
                                    vm.updateSettings(
                                        settings.copy(
                                            networkSetting = settings.networkSetting.copy(cherryCompatMode = checked)
                                        )
                                    )
                                },
                            )
                        },
                    )
                }
            }
            item {
                // v3.15.0: 自动重试开关 (2.4.16 移植) — false = 断联直接报错
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_preferences_network_auto_retry)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_preferences_network_auto_retry_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.networkSetting.enableAutoRetry,
                                onCheckedChange = { checked ->
                                    vm.updateSettings(
                                        settings.copy(networkSetting = settings.networkSetting.copy(enableAutoRetry = checked))
                                    )
                                },
                            )
                        },
                    )
                }
            }
            // v3.12.6: 密钥专项预热开关 — 用户可选 (默认关), 与软件本体
            // 启动预热拆分; 定向预热在部分网络下会同 key 并发被服务端
            // 串行化反而拉长首字节, 由用户按网络环境自行决定
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("密钥预热") },
                ) {
                    item(
                        headlineContent = { Text("OpenCode 预热") },
                        supportingContent = { Text("应用启动时预热 OpenCode 网关连接") },
                        trailingContent = {
                            Switch(
                                checked = settings.opencodeWarmEnabled,
                                onCheckedChange = { checked ->
                                    vm.updateSettings(settings.copy(opencodeWarmEnabled = checked))
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("Command Code 预热") },
                        supportingContent = { Text("应用启动时预热 Command Code 网关连接") },
                        trailingContent = {
                            Switch(
                                checked = settings.commandCodeWarmEnabled,
                                onCheckedChange = { checked ->
                                    vm.updateSettings(settings.copy(commandCodeWarmEnabled = checked))
                                },
                            )
                        },
                    )
                }
            }
            item {
                // v3.13.3: CC 图片兼容适配 (opt-in) — CC 网关严格校验图片格式,
                // GIF/SVG 等会触发 Invalid input 并 4 次重试卡死; 开启后
                // 自动转 JPEG/剔除不兼容图, 仅影响 Command Code 通道
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("Command Code 图片兼容") },
                ) {
                    item(
                        headlineContent = { Text("图片自动适配") },
                        supportingContent = { Text("修复 Command Code 通道图片卡死: 工具返回的图片转为规范格式发送 (对齐 Cherry Studio)。仅影响 Command Code 通道") },
                        trailingContent = {
                            Switch(
                                checked = settings.ccImageCompat,
                                onCheckedChange = { checked ->
                                    vm.updateSettings(settings.copy(ccImageCompat = checked))
                                },
                            )
                        },
                    )
                }
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = {
                        Text(stringResource(R.string.setting_page_preferences_network_proxy))
                    },
                ) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_preferences_network_proxy_enable))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.networkSetting.proxyEnabled,
                                onCheckedChange = { checked ->
                                    vm.updateSettings(
                                        settings.copy(
                                            networkSetting = settings.networkSetting.copy(proxyEnabled = checked)
                                        )
                                    )
                                }
                            )
                        },
                    )
                    if (settings.networkSetting.proxyEnabled) {
                        item(
                            headlineContent = {
                                Text(stringResource(R.string.setting_page_preferences_network_proxy_partial))
                            },
                            supportingContent = {
                                Text(stringResource(R.string.setting_page_preferences_network_proxy_partial_desc))
                            },
                            trailingContent = {
                                Switch(
                                    checked = settings.networkSetting.proxyPartialEnabled,
                                    onCheckedChange = { checked ->
                                        vm.updateSettings(
                                            settings.copy(
                                                networkSetting = settings.networkSetting.copy(proxyPartialEnabled = checked)
                                            )
                                        )
                                    }
                                )
                            },
                        )
                        if (settings.networkSetting.proxyPartialEnabled) {
                            item(
                                onClick = { modelPickerVisible = true },
                                headlineContent = {
                                    Text(stringResource(R.string.setting_page_preferences_network_proxy_models))
                                },
                                supportingContent = {
                                    Text(
                                        if (settings.networkSetting.proxyModelIds.isEmpty()) {
                                            stringResource(R.string.setting_page_preferences_network_proxy_models_empty)
                                        } else {
                                            stringResource(R.string.setting_page_preferences_network_proxy_models_count, settings.networkSetting.proxyModelIds.size)
                                        }
                                    )
                                },
                                trailingContent = {
                                    Icon(HugeIcons.ArrowRight01, contentDescription = null)
                                },
                            )
                        }
                    }
                    item(
                        onClick = {
                            proxyUrlDraft = proxyUrl
                            proxyUsernameDraft = proxyUsername
                            proxyPasswordDraft = proxyPassword
                            proxyPasswordVisible = false
                            proxyDialogVisible = true
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_config))
                        },
                        supportingContent = {
                            Text(
                                if (proxyUrl.isBlank()) {
                                    stringResource(
                                        R.string.setting_page_preferences_network_proxy_desc
                                    )
                                } else {
                                    proxyUrl
                                }
                            )
                        },
                        trailingContent = {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_provider_page_test_connection))
                        },
                        trailingContent = {
                            TextButton(
                                onClick = ::testProxy,
                                enabled = settings.networkSetting.proxyEnabled && proxyUrl.toProxyOrNull() != null && !proxyTesting,
                            ) {
                                if (proxyTesting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Text(stringResource(R.string.setting_provider_page_test))
                                }
                            }
                        },
                    )
                }
            }
        }
    }
    // v3.9.15: 部分开启 — 提供商模型多选弹窗
    if (modelPickerVisible) {
        val providers = settings.providers.filter { it.models.isNotEmpty() }
        AlertDialog(
            onDismissRequest = { modelPickerVisible = false },
            title = { Text(stringResource(R.string.setting_page_preferences_network_proxy_models)) },
            text = {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (providers.isEmpty()) {
                        Text(stringResource(R.string.setting_page_preferences_network_proxy_models_none))
                    } else {
                        providers.forEach { provider ->
                            Text(
                                provider.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            provider.models.forEach { model ->
                                val checked = model.modelId in settings.networkSetting.proxyModelIds
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val newList = if (checked) {
                                                settings.networkSetting.proxyModelIds - model.modelId
                                            } else {
                                                settings.networkSetting.proxyModelIds + model.modelId
                                            }
                                            vm.updateSettings(
                                                settings.copy(
                                                    networkSetting = settings.networkSetting.copy(proxyModelIds = newList)
                                                )
                                            )
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    androidx.compose.material3.Checkbox(
                                        checked = checked,
                                        onCheckedChange = null,
                                    )
                                    androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 4.dp))
                                    Text(
                                        if (model.displayName.isNotBlank()) model.displayName else model.modelId,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { modelPickerVisible = false }) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

}