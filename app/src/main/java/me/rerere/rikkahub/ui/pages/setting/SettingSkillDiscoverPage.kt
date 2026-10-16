/* 【域 E·设置体系】 | 地图: docs/APP_MAP.md §E */
package me.rerere.rikkahub.ui.pages.setting

/* ───【自研】SettingSkillDiscoverPage.kt — v4.8.145 技能「发现」(CS v0.2.1 移植)
 * 两个提供来源: CherryIN / skills.sh — 搜索 + 热门 + 详情/安装 + 从链接安装。
 * 安装落到本机技能目录 (SkillManager 原子入盘), 在「Agent Skills」(我的技能) 中即现。
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.skill.DiscoverSkillItem
import me.rerere.rikkahub.data.skill.SkillDiscoverService
import me.rerere.rikkahub.data.skill.SkillDiscoverSource
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

@Composable
fun SettingSkillDiscoverContent() {
    val toaster = LocalToaster.current
    val skillManager: SkillManager = koinInject()
    var source by remember { mutableStateOf(SkillDiscoverSource.CHERRYIN) }
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<DiscoverSkillItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var nextOffset by remember { mutableStateOf<Int?>(0) }
    var installing by remember { mutableStateOf<String?>(null) }
    var urlInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun load(reset: Boolean) {
        if (loading) return
        scope.launch {
            loading = true
            error = null
            try {
                when (source) {
                    SkillDiscoverSource.CHERRYIN -> {
                        val offset = if (reset) 0 else (nextOffset ?: return@launch)
                        val (list, next) = SkillDiscoverService.cherryinList(query, offset)
                        items = if (reset) list else items + list
                        nextOffset = next
                    }
                    SkillDiscoverSource.SKILLS_SH -> {
                        items = if (query.isBlank()) {
                            SkillDiscoverService.skillsShPopular()
                        } else {
                            SkillDiscoverService.skillsShSearch(query)
                        }
                        nextOffset = null
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(source) {
        query = ""
        items = emptyList()
        nextOffset = 0
        load(reset = true)
    }

    fun install(item: DiscoverSkillItem) {
        if (installing != null) return
        scope.launch {
            installing = item.key
            try {
                val name = when (source) {
                    SkillDiscoverSource.CHERRYIN -> SkillDiscoverService.installFromCherryin(
                        skillManager,
                        item.cherryinId ?: "",
                        item.name,
                    )
                    SkillDiscoverSource.SKILLS_SH -> SkillDiscoverService.installFromGithub(
                        skillManager,
                        item.githubOwner ?: "",
                        item.githubRepo ?: "",
                        item.githubSkillName,
                    )
                }
                toaster.show(message = "已安装「$name」— 可在「我的技能」中查看", type = ToastType.Success)
            } catch (e: Exception) {
                toaster.show(message = e.message ?: "安装失败", type = ToastType.Error)
            } finally {
                installing = null
            }
        }
    }

    fun installFromUrl() {
        val raw = urlInput.trim()
        if (raw.isBlank()) {
            toaster.show(message = "请输入 GitHub / skills.sh 链接", type = ToastType.Warning)
            return
        }
        if (installing != null) return
        scope.launch {
            installing = "url"
            try {
                val name = SkillDiscoverService.installFromUrl(skillManager, raw)
                toaster.show(message = "已安装「$name」— 可在「我的技能」中查看", type = ToastType.Success)
                urlInput = ""
            } catch (e: Exception) {
                toaster.show(message = e.message ?: "安装失败", type = ToastType.Error)
            } finally {
                installing = null
            }
        }
    }

    // v4.8.146: 内容化 — 由 SkillHubPage「发现」Tab 承载 (顶栏/返回由 Hub 提供)
    Column(Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = source.ordinal) {
                Tab(
                    selected = source == SkillDiscoverSource.CHERRYIN,
                    onClick = { source = SkillDiscoverSource.CHERRYIN },
                    text = { Text("CherryIN") },
                )
                Tab(
                    selected = source == SkillDiscoverSource.SKILLS_SH,
                    onClick = { source = SkillDiscoverSource.SKILLS_SH },
                    text = { Text("skills.sh") },
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text("查找技能") },
                trailingIcon = {
                    IconButton(onClick = { load(reset = true) }) {
                        Icon(HugeIcons.GlobalSearch, null)
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { load(reset = true) }),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("从链接安装：GitHub / skills.sh") },
                )
                Button(
                    onClick = { installFromUrl() },
                    enabled = installing == null,
                ) { Text(if (installing == "url") "安装中…" else "安装") }
            }
            when {
                error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("加载失败：$error", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { load(reset = true) }) { Text("重试") }
                        }
                    }
                }
                items.isEmpty() && loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                items.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("未找到匹配的技能", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                else -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(items, key = { it.key }) { item ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(item.name, style = MaterialTheme.typography.titleSmall)
                                        if (item.description.isNotBlank()) {
                                            Text(
                                                item.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Text(
                                            buildString {
                                                append(item.sourceLabel)
                                                item.installs?.let {
                                                    append(" · ").append(formatInstalls(it)).append(" 次安装")
                                                }
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    when {
                                        !item.installable -> Text(
                                            "无安装包",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        installing == item.key -> Text(
                                            "安装中…",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                        else -> TextButton(
                                            onClick = { install(item) },
                                            enabled = installing == null,
                                        ) { Text("安装") }
                                    }
                                }
                            }
                        }
                        if (source == SkillDiscoverSource.CHERRYIN && nextOffset != null) {
                            item("more") {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    TextButton(
                                        onClick = { load(reset = false) },
                                        enabled = !loading,
                                    ) { Text(if (loading) "加载中…" else "加载更多") }
                                }
                            }
                        }
                    }
                }
            }
    }
}

private fun formatInstalls(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}
