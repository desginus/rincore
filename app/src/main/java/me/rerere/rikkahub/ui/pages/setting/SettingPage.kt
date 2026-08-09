package me.rerere.rikkahub.ui.pages.setting

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.BookOpen01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.Clapping01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.ImageUpload
import me.rerere.hugeicons.stroke.InLove
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Settings01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.WavingHand01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.isNotConfigured
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.rememberColorMode
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val filesManager: FilesManager = koinInject()
    var showDomainPage by remember { mutableStateOf(false) }
    var showToolHubPage by remember { mutableStateOf(false) }
    var showToolComparePage by remember { mutableStateOf(false) }
    var showBuiltinToolsPage by remember { mutableStateOf(false) }

    if (showToolHubPage) {
        SettingToolHubPage(
            onOpenMcp = {
                showToolHubPage = false
                navController.navigate(Screen.SettingMcp)
            },
            onOpenCompare = {
                showToolHubPage = false
                showToolComparePage = true
            },
            onOpenAbout = {
                showToolHubPage = false
                navController.navigate(Screen.SettingAbout)
            },
            onBack = { showToolHubPage = false },
        )
        return
    }

    if (showToolComparePage) {
        SettingToolComparePage(
            settings = settings,
            onBack = { showToolComparePage = false },
        )
        return
    }

    if (showBuiltinToolsPage) {
        SettingBuiltinToolsPage(onBack = { showBuiltinToolsPage = false })
        return
    }

    if (showDomainPage) {
        SettingDomainPage(
            settings = settings,
            vm = vm,
            onBack = { showDomainPage = false },
        )
        return
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(text = stringResource(R.string.settings))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (settings.isNotConfigured()) {
                item {
                    ProviderConfigWarningCard(navController)
                }
            }

            item("generalSettings") {
                val context = LocalContext.current
                var colorMode by rememberColorMode()
                val selectedColorModeText = when (colorMode) {
                    ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
                    ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
                    ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
                }
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("通用与外观") },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.Sun01, null) },
                        trailingContent = {
                            Select(
                                options = ColorMode.entries,
                                selectedOption = colorMode,
                                onOptionSelected = {
                                    colorMode = it
                                    navController.navigate(Screen.Setting) {
                                        popUpTo(Screen.Setting) {
                                            inclusive = true
                                        }
                                    }
                                },
                                optionToString = {
                                    when (it) {
                                        ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
                                        ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
                                        ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
                                    }
                                },
                                modifier = Modifier.width(150.dp)
                            )
                        },
                        headlineContent = { Text(stringResource(R.string.setting_page_color_mode)) },
                        supportingContent = { Text(selectedColorModeText) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingPreferences) },
                        leadingContent = { Icon(HugeIcons.Settings03, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_preferences_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_preferences)) },
                    )
                                                        }
            }
            
            item("assistantManagement") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("助手管理") },
                ) {
item(
                        onClick = { navController.navigate(Screen.Assistant) },
                        leadingContent = { Icon(HugeIcons.LookTop, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_assistant_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_assistant)) },
                    )
item(
                        onClick = { navController.navigate(Screen.AssistantBasic(defaultAssistant.id.toString())) },
                        leadingContent = { Icon(HugeIcons.Settings03, null) },
                        supportingContent = { Text("当前默认：${defaultAssistant.name}") },
                        headlineContent = { Text("基础设定") },
                    )
item(
                        onClick = { navController.navigate(Screen.AssistantPrompt(defaultAssistant.id.toString())) },
                        leadingContent = { Icon(HugeIcons.Message02, null) },
                        supportingContent = { Text("当前默认：${defaultAssistant.name}") },
                        headlineContent = { Text("提示词") },
                    )
item(
                        onClick = { navController.navigate(Screen.AssistantMemory(defaultAssistant.id.toString())) },
                        leadingContent = { Icon(HugeIcons.Brain02, null) },
                        supportingContent = { Text("当前默认：${defaultAssistant.name}") },
                        headlineContent = { Text("记忆") },
                    )
item(
                        onClick = { navController.navigate(Screen.AssistantLocalTool(defaultAssistant.id.toString())) },
                        leadingContent = { Icon(HugeIcons.BookOpen01, null) },
                        supportingContent = { Text("当前默认：${defaultAssistant.name}") },
                        headlineContent = { Text("本地工具") },
                    )

                }
            }
            item("modelServices") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("模型与服务") },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingModels) },
                        leadingContent = { Icon(HugeIcons.AiMagic, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_default_model_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_default_model)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingProvider) },
                        leadingContent = { Icon(HugeIcons.Brain02, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_providers_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_providers)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingSearch) },
                        leadingContent = { Icon(HugeIcons.GlobalSearch, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_search_service_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_search_service)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingSpeech) },
                        leadingContent = { Icon(HugeIcons.Megaphone01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_tts_service_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_tts_service)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingWeb) },
                        leadingContent = { Icon(HugeIcons.ServerStack01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_web_server_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server)) },
                    )
                }
            }
            item("extensionsTools") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("扩展与工具") },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.Extensions) },
                        leadingContent = { Icon(HugeIcons.Package, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_extensions_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_extensions)) },
                    )
                    item(
                        onClick = { showToolHubPage = true },
                        leadingContent = { Icon(HugeIcons.ServerStack01, null) },
                        supportingContent = { Text("工具对照 / MCP 服务器 / 关于和分享（统一收口）") },
                        headlineContent = { Text("工具管理") },
                    )
                    item(
                        onClick = { showBuiltinToolsPage = true },
                        leadingContent = { Icon(HugeIcons.Settings03, null) },
                        supportingContent = { Text("精确到工具 ID 与数量的全量清单（核对工具总数）") },
                        headlineContent = { Text("内置工具") },
                    )
                    item(
                        onClick = { showDomainPage = true },
                        leadingContent = { Icon(HugeIcons.Settings03, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_domain_management_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_domain_management)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Skills) },
                        leadingContent = { Icon(HugeIcons.Puzzle, null) },
                        supportingContent = { Text("Agent Skills 能力模块（与 MCP 同等次，一个 Skill 一个工具）") },
                        headlineContent = { Text("Agent Skills") },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Workflows) },
                        leadingContent = { Icon(HugeIcons.AiEditing, null) },
                        supportingContent = { Text("触发器 + 条件 + 动作序列") },
                        headlineContent = { Text("工作流") },
                    )
                    item(
                        onClick = { navController.navigate(Screen.ScheduledJobs) },
                        leadingContent = { Icon(HugeIcons.Book01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_scheduled_jobs_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_scheduled_jobs)) },
                    )
                }
            }
            item("dataSettings") {
                val storageState by produceState(-1 to 0L) {
                    value = filesManager.countChatFiles()
                }
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("系统与权限") },
                ) {
item(
                        onClick = { navController.navigate(Screen.SettingPermissions) },
                        leadingContent = { Icon(HugeIcons.Settings01, null) },
                        supportingContent = { Text("权限自动发现 · 后台保障链引导") },
                        headlineContent = { Text("权限管理") },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingAlarms) },
                        leadingContent = { Icon(HugeIcons.Clock02, null) },
                        supportingContent = { Text("设备闹钟") },
                        headlineContent = { Text("闹钟") },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Backup) },
                        leadingContent = { Icon(HugeIcons.Database02, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_data_backup_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_data_backup)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingFiles) },
                        leadingContent = { Icon(HugeIcons.ImageUpload, null) },
                        supportingContent = {
                            if (storageState.first == -1) {
                                Text(stringResource(R.string.calculating))
                            } else {
                                Text(
                                    stringResource(
                                        R.string.setting_page_chat_storage_desc,
                                        storageState.first,
                                        storageState.second / 1024 / 1024.0
                                    )
                                )
                            }
                        },
                        headlineContent = { Text(stringResource(R.string.setting_page_chat_storage)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingCallTrace) },
                        leadingContent = { Icon(HugeIcons.Code, null) },
                        supportingContent = { Text("查看最近一条消息的完整运行流程") },
                        headlineContent = { Text("运行日志") },
                    )
                }
            }
            item("aboutSettings") {
                val context = LocalContext.current
                val shareText = stringResource(R.string.setting_page_share_text)
                val share = stringResource(R.string.setting_page_share)
                val noShareApp = stringResource(R.string.setting_page_no_share_app)
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_about)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.Log) },
                        leadingContent = { Icon(HugeIcons.Bookshelf01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_request_logs_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_request_logs)) },
                    )
                    item(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND)
                            intent.type = "text/plain"
                            intent.putExtra(Intent.EXTRA_TEXT, shareText)
                            try {
                                context.startActivity(Intent.createChooser(intent, share))
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(context, noShareApp, Toast.LENGTH_SHORT).show()
                            }
                        },
                        leadingContent = { Icon(HugeIcons.Share04, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_share_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_share)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderConfigWarningCard(navController: Navigator) {
    Card(
        modifier = Modifier.padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            ListItem(
                supportingContent = {
                    Text(stringResource(R.string.setting_page_config_api_desc))
                },
                leadingContent = {
                    Icon(HugeIcons.Alert01, null)
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                ),
) {
                   Text(stringResource(R.string.setting_page_config_api_title))
               }

            TextButton(
                onClick = {
                    navController.navigate(Screen.SettingProvider)
                }
            ) {
                Text(stringResource(R.string.setting_page_config))
            }
        }
    }
}
