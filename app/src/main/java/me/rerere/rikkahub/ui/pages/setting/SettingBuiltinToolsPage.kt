/* 【域 E·设置体系】 | 地图: docs/APP_MAP.md §E */
package me.rerere.rikkahub.ui.pages.setting


/* ───【自研】SettingBuiltinToolsPage.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 *
 * v5.1.0 重写（用户要求）：内置工具原先只有一行"已启用 N 个"的只读列表，
 * 既看不到全部工具、也不能开关。现在：
 *   ① 按能力**分类**（5 类，见 TOOL_GROUPS）；
 *   ② 每个工具一行**开关**，直接写当前助手的 `localTools`（与模型侧注入同源）；
 *   ③ 顶部**搜索**（中文名 / 说明 / 内部键 / 分类名 都能命中）；
 *   ④ 开关状态从 `settingsFlow` 响应式读取，切换后即时刷新。
 * ───────────────────────────────────────────────────────────────*/
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import org.koin.compose.koinInject

/** 分类（v5.1.0）：按"能力域"分组，同组工具通常一起开/一起关。 */
private data class ToolGroup(
    val title: String,
    val hint: String,
    val options: List<LocalToolOption>,
)

private val TOOL_GROUPS: List<ToolGroup> = listOf(
    ToolGroup(
        title = "系统与设备",
        hint = "读取手机状态、时间和位置，控制剪贴板/朗读/媒体与通知",
        options = listOf(
            LocalToolOption.TimeInfo,
            LocalToolOption.Battery,
            LocalToolOption.ScreenTime,
            LocalToolOption.Location,
            LocalToolOption.Clipboard,
            LocalToolOption.Tts,
            LocalToolOption.MediaPlayer,
            LocalToolOption.MediaScanner,
            LocalToolOption.ToastAndNotification,
            LocalToolOption.Calendar,
        ),
    ),
    ToolGroup(
        title = "文件与媒体",
        hint = "读写文件、下载、压缩包处理与分享",
        options = listOf(
            LocalToolOption.Files,
            LocalToolOption.Download,
            LocalToolOption.Archive,
            LocalToolOption.Share,
        ),
    ),
    ToolGroup(
        title = "网络与浏览",
        hint = "网页抓取与浏览器自动化",
        options = listOf(
            LocalToolOption.Browser,
        ),
    ),
    ToolGroup(
        title = "自动化与任务",
        hint = "定时、工作流、子代理与系统跳转",
        options = listOf(
            LocalToolOption.Alarm,
            LocalToolOption.CronJobs,
            LocalToolOption.Workflows,
            LocalToolOption.SubAgents,
            LocalToolOption.SystemIntents,
        ),
    ),
    ToolGroup(
        title = "计算与交互",
        hint = "代码计算、图表、反问用户与用量护栏",
        options = listOf(
            LocalToolOption.JavascriptEngine,
            LocalToolOption.ChartDisplay,
            LocalToolOption.AskUser,
            LocalToolOption.CostGuards,
        ),
    ),
)

/** 中文名 + 一句说明（搜索同时匹配两者与内部键）。 */
private fun labelOf(option: LocalToolOption): String = when (option) {
    LocalToolOption.JavascriptEngine -> "JavaScript 计算"
    LocalToolOption.TimeInfo -> "时间信息"
    LocalToolOption.Clipboard -> "剪贴板"
    LocalToolOption.Tts -> "语音朗读"
    LocalToolOption.Battery -> "电池状态"
    LocalToolOption.Workflows -> "工作流"
    LocalToolOption.Alarm -> "闹钟"
    LocalToolOption.AskUser -> "反问用户"
    LocalToolOption.ScreenTime -> "屏幕使用时间"
    LocalToolOption.Calendar -> "日历"
    LocalToolOption.ChartDisplay -> "图表展示"
    LocalToolOption.CronJobs -> "定时任务"
    LocalToolOption.ToastAndNotification -> "提示与通知"
    LocalToolOption.SubAgents -> "子代理"
    LocalToolOption.SystemIntents -> "系统跳转"
    LocalToolOption.Share -> "分享"
    LocalToolOption.Location -> "位置"
    LocalToolOption.MediaPlayer -> "媒体播放"
    LocalToolOption.MediaScanner -> "媒体扫描"
    LocalToolOption.Files -> "文件读写"
    LocalToolOption.Download -> "下载"
    LocalToolOption.Archive -> "压缩包"
    LocalToolOption.CostGuards -> "用量护栏"
    LocalToolOption.Browser -> "浏览器"
}

private fun descOf(option: LocalToolOption): String = when (option) {
    LocalToolOption.JavascriptEngine -> "沙箱内执行 JS 做计算/数据处理"
    LocalToolOption.TimeInfo -> "当前时间、时区与格式化"
    LocalToolOption.Clipboard -> "读取/写入系统剪贴板"
    LocalToolOption.Tts -> "把文本念出来"
    LocalToolOption.Battery -> "电量、充电状态与省电模式"
    LocalToolOption.Workflows -> "运行预定义的多步工作流"
    LocalToolOption.Alarm -> "设置一次性闹钟"
    LocalToolOption.AskUser -> "生成选项向用户提问并等待回答"
    LocalToolOption.ScreenTime -> "查询各应用使用时长（需使用情况访问权限）"
    LocalToolOption.Calendar -> "查询与创建日历事件"
    LocalToolOption.ChartDisplay -> "把数据渲染成图表卡片"
    LocalToolOption.CronJobs -> "创建周期任务（定时触发对话）"
    LocalToolOption.ToastAndNotification -> "弹提示 / 发系统通知"
    LocalToolOption.SubAgents -> "派生独立子代理并行处理子任务"
    LocalToolOption.SystemIntents -> "调起系统页面或其它应用"
    LocalToolOption.Share -> "把内容分享给其它应用"
    LocalToolOption.Location -> "获取当前位置"
    LocalToolOption.MediaPlayer -> "播放音频/视频"
    LocalToolOption.MediaScanner -> "刷新媒体库索引"
    LocalToolOption.Files -> "读取、写入、搜索文件"
    LocalToolOption.Download -> "从网络下载文件"
    LocalToolOption.Archive -> "解压/打包 zip、tar 等"
    LocalToolOption.CostGuards -> "限制单次会话的用量与成本"
    LocalToolOption.Browser -> "打开网页、抓正文、点击与输入"
}

/** 内置工具 — 分类 + 开关 + 搜索（v5.1.0 重写；开关直接绑定当前助手） */
@Composable
fun SettingBuiltinToolsPage(
    onBack: () -> Unit,
) {
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val assistant = settings.getCurrentAssistant()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }

    val enabled = remember(assistant.localTools) { assistant.localTools.toSet() }

    fun setEnabled(option: LocalToolOption, on: Boolean) {
        scope.launch {
            settingsStore.update { st ->
                st.copy(
                    assistants = st.assistants.map { a ->
                        if (a.id != assistant.id) a
                        else a.copy(
                            localTools = if (on) (a.localTools + option).distinct()
                            else a.localTools.filterNot { it == option }
                        )
                    }
                )
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // v4.8.11: 返回层级修复 — 内嵌子页面拦截系统返回, 先回上一级
        BackHandler { onBack() }
        TopAppBar(
            title = { Text("内置工具") },
            navigationIcon = {
                TextButton(onClick = onBack) { Text("返回") }
            },
        )

        val q = query.trim()
        val groups = TOOL_GROUPS
            .map { g ->
                g.copy(
                    options = g.options.filter { o ->
                        q.isBlank() ||
                            labelOf(o).contains(q, ignoreCase = true) ||
                            descOf(o).contains(q, ignoreCase = true) ||
                            o.toString().contains(q, ignoreCase = true) ||
                            g.title.contains(q, ignoreCase = true)
                    }
                )
            }
            .filter { it.options.isNotEmpty() }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索工具（中文名 / 说明 / 内部键 / 分类）") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )

        Text(
            "当前助手：${assistant.name} · 已启用 ${enabled.size} 个" +
                if (q.isNotBlank()) " · 匹配 ${groups.sumOf { it.options.size }} 个" else "",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.primary,
        )

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        ) {
            if (groups.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "没有匹配「$q」的工具",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            groups.forEach { group ->
                item(key = "h_" + group.title) {
                    Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                        Text(
                            group.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            group.hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(group.options, key = { it.toString() }) { option ->
                    val on = option in enabled
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                labelOf(option),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                descOf(option),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                option.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = on, onCheckedChange = { setEnabled(option, it) })
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
            item(key = "footer") { Spacer(Modifier.height(24.dp)) }
        }
    }
}
