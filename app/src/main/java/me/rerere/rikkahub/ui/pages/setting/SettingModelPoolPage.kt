/* 【域 E·设置体系】 */
package me.rerere.rikkahub.ui.pages.setting

/* ───【自研】SettingModelPoolPage.kt — 原版无此文件（v5.1.4 新增）
 * 「快速模型配置」/「极致模型配置」：从全部提供商模型里多选，组成竞速池。
 * 主对话页选「快速/极致」时，客户端会并发探测池内模型、取响应最快的一个。
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.compose.koinInject

/**
 * @param mode 1 = 快速池，2 = 极致池
 */
@Composable
fun SettingModelPoolPage(
    mode: Int,
    onBack: () -> Unit,
) {
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val isFast = mode == 1
    val title = if (isFast) "快速模型配置" else "极致模型配置"
    val selected: List<Uuid> = if (isFast) settings.fastChatModelIds else settings.ultraChatModelIds

    fun save(next: List<Uuid>) {
        scope.launch {
            settingsStore.update { st ->
                if (isFast) st.copy(fastChatModelIds = next) else st.copy(ultraChatModelIds = next)
            }
        }
    }

    BackHandler { onBack() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                TextButton(onClick = onBack) { Text("返回") }
            },
        )

        Text(
            "从下面的模型里选一个或多个。开始对话时会**同时探测**这些模型，取当前响应最快的一个来回答。" +
                "池为空 = 未启用（选「" + (if (isFast) "快速" else "极致") + "」时自动回退普通模型）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Text(
            "已选 ${selected.size} 个" + if (selected.isEmpty()) "（未配置）" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            settings.providers.forEach { provider ->
                // v5.1.5 ★核验：禁用的提供商的模型不该进池（选了也调不通）
                if (!provider.enabled || provider.models.isEmpty()) return@forEach
                item(key = "h_" + provider.id) {
                    Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                        Text(
                            provider.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "该提供商 ${provider.models.size} 个模型",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(provider.models, key = { provider.id.toString() + "_" + it.id }) { model ->
                    val checked = model.id in selected
                    // ═══ v5.3.35 ★能力准入的可视化 ═══
                    // 「快速/极致」池现在只接受**声明了工具能力（TOOL）**的模型：
                    // ChatCompletionsAPI 只在 abilities 含 TOOL 时才把 tools 定义放进请求，
                    // 否则模型拿不到工具定义、却能看到历史里的工具调用痕迹 →
                    // **把工具调用模仿成正文吐出来**（用户实测：DeepSeek 路由到智谱后工具调用变正文）。
                    val toolCapable = model.abilities.contains(me.rerere.ai.provider.ModelAbility.TOOL)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                model.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                model.modelId,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!toolCapable) {
                                Text(
                                    "⚠ 未声明工具能力（TOOL）· 不会进入候选 —— " +
                                        "否则它拿不到工具定义，会把工具调用当正文输出",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Checkbox(
                            checked = checked,
                            // 不合格的模型直接不可勾选（already-selected 的仍显示勾，便于用户手动移除）
                            enabled = toolCapable || checked,
                            onCheckedChange = { on ->
                                save(if (on) (selected + model.id).distinct() else selected - model.id)
                            },
                        )
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }
            item(key = "footer") { Spacer(Modifier.height(24.dp)) }
        }
    }
}
