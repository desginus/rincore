package me.rerere.rikkahub.ui.pages.market


/* ───【自研】岔路口计划·阶段1 — 应用市场详情页
 * 来源: RinCore 自研新增
 * ───────────────────────────────────────────────────────────────*/
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketDetailPage(
    entryId: String,
) {
    val vm: MarketVM = koinInject()
    val state by vm.detailState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(entryId) { vm.loadDetail(entryId) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(state.entry?.title ?: "详情", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (state.loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val entry = state.entry ?: run {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(state.message ?: "条目不存在", color = MaterialTheme.colorScheme.error)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding + PaddingValues(16.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 头部: logo + 标题 + 作者
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (!entry.logoUrl.isNullOrBlank()) {
                    AsyncImage(model = entry.logoUrl, contentDescription = null, modifier = Modifier.size(64.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.title.ifBlank { entry.id }, style = MaterialTheme.typography.titleLarge)
                    val author = entry.author?.login ?: entry.publisher?.login ?: entry.authorId
                    if (!author.isNullOrBlank()) Text("作者: $author", style = MaterialTheme.typography.bodySmall)
                }
            }

            // 元信息 chips
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = {}, label = { Text(entry.type) })
                AssistChip(onClick = {}, label = { Text("下载 ${entry.effectiveDownloadCount}") })
                entry.latestVersion?.let {
                    AssistChip(onClick = {}, label = { Text("v${it.version}") })
                    AssistChip(onClick = {}, label = { Text(it.formatVer) })
                }
            }

            // 描述
            if (entry.description.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("简介", style = MaterialTheme.typography.titleSmall)
                        Text(entry.description, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (entry.detail.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("详情", style = MaterialTheme.typography.titleSmall)
                        Text(entry.detail, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // 安装/卸载/更新 区域
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val installed = state.installed
                    when {
                        state.installing -> {
                            Text("安装中…", style = MaterialTheme.typography.titleSmall)
                            LinearProgressIndicator(
                                progress = { state.installProgress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        installed != null -> {
                            Text("已安装 v${installed.version}", style = MaterialTheme.typography.titleSmall)
                            val sameVer = installed.version == entry.latestVersion?.version
                            if (!sameVer) {
                                Button(
                                    onClick = { vm.install(entry) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("更新到 v${entry.latestVersion?.version ?: ""}") }
                            }
                            OutlinedButton(
                                onClick = { vm.uninstall(entry.id) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("卸载") }
                            // v4.5.29 阶段2: 脚本启用开关 (启用后工具注册到「插件」域)
                            if (installed.type == "script") {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("启用脚本工具", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            if (installed.enabled) "工具已注册到「插件」域, 模型可调用"
                                            else "启用后脚本工具才会注入模型工具池",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = installed.enabled,
                                        onCheckedChange = { vm.setScriptEnabled(entry.id, it) },
                                    )
                                }
                            } else if (installed.type == "mcp") {
                                // v4.5.30 阶段3: MCP 服务器导入开关
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("启用 MCP 服务器", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            if (installed.enabled) "已加入 MCP 配置并启用 (设置 → MCP)"
                                            else "已暂停 (配置保留, 可随时重新启用)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = installed.enabled,
                                        onCheckedChange = { vm.setScriptEnabled(entry.id, it) },
                                    )
                                }
                            } else if (installed.type == "skill") {
                                Text(
                                    "已导入到技能体系 (能力 → Agent Skills), 模型可直接使用",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else if (installed.type == "package") {
                                Text(
                                    "包类型 (ToolPkg) 运行时支持即将上线 — 当前已下载到本地",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        else -> {
                            Button(
                                onClick = { vm.install(entry) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("安装") }
                        }
                    }
                    state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
