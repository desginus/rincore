package me.rerere.rikkahub.ui.pages.setting

/*
 * v4.5.5: 客户端设置 — 图片/文件上传模式 (用户可选, 请求体严格按所选模式构造)。
 * 每类模式二选一 (经典/兼容), 互斥单选; 默认值为当前线上行为 (图片=经典, 文件=兼容)。
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingClientPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("客户端设置") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "以下模式严格决定请求体的构造方式。切换后立即对后续所有请求生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("图片上传模式", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "决定图片以何种形态进入请求体",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        UploadModeOption(
                            title = "经典",
                            desc = "图片 Base64 内联供模型直接查看；超出 8 张预算的持久降级为路径占位；工具产出的图转移到随后的用户消息。",
                            selected = settings.imageUploadMode == "classic",
                            onClick = { vm.updateSettings(settings.copy(imageUploadMode = "classic")) },
                        )
                        UploadModeOption(
                            title = "兼容",
                            desc = "旧版形态：工具产出的图以内嵌 image_url 留在工具结果中，不转移、不降级。适用于接受工具结果带图的网关。",
                            selected = settings.imageUploadMode == "compat",
                            onClick = { vm.updateSettings(settings.copy(imageUploadMode = "compat")) },
                        )
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("文件上传模式", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "决定 PDF/DOCX 等文件以何种形态进入请求体",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        UploadModeOption(
                            title = "经典",
                            desc = "旧版形态：全文提取并内联进请求 (大文件会显著增大请求体并影响缓存)。",
                            selected = settings.fileUploadMode == "classic",
                            onClick = { vm.updateSettings(settings.copy(fileUploadMode = "classic")) },
                        )
                        UploadModeOption(
                            title = "兼容",
                            desc = "当前形态：文件以内联占位替换，仅携带精确工作区路径，模型需要内容时用 workspace_read_file 按需读取。请求短小、缓存稳定。",
                            selected = settings.fileUploadMode == "compat",
                            onClick = { vm.updateSettings(settings.copy(fileUploadMode = "compat")) },
                        )
                    }
                }
            }
            item {
                // v4.5.17: 仿 OpenCode 请求模式 — 对 opencode.ai 网关按模型协议映射
                // (与 OpenCode 客户端同源: models.dev 每模型 npm 决定传输协议)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("仿 OpenCode 请求模式", style = MaterialTheme.typography.titleMedium)
                            }
                            Switch(
                                checked = settings.opencodeRequestMode,
                                onCheckedChange = { vm.updateSettings(settings.copy(opencodeRequestMode = it)) },
                            )
                        }
                        Text(
                            "开启后，发往 OpenCode 网关 (opencode.ai) 的请求严格对齐 OpenCode 客户端：按每个模型的传输协议 (Chat Completions / Responses / Anthropic / Google) 自动分派，" +
                                "使 Zen/Go 上仅支持 Responses 或 Anthropic 等协议的模型可用。关闭时请求行为与现在完全一致。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadModeOption(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(top = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
