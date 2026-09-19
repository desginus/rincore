package me.rerere.rikkahub.ui.pages.market


/* ───【自研】岔路口计划·阶段1 — 应用市场列表页
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Sorting01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.operit.market.MarketEntry
import me.rerere.rikkahub.data.operit.market.MarketSort
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketPage() {
    val vm: MarketVM = koinInject()
    val state by vm.listState.collectAsStateWithLifecycle()
    val installedMap by vm.installedMap.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()

    // 滚动到底部自动加载下一页
    val reachedBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItems - 5
        }
    }
    LaunchedEffect(reachedBottom) {
        if (reachedBottom && !state.loading && state.entries.size < state.total) {
            vm.loadNextPage()
        }
    }

    val typeTabs = listOf<Pair<String, String?>>(
        "全部" to null,
        "脚本" to "script",
        "工具包" to "package",
        "技能" to "skill",
        "MCP" to "mcp",
    )

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("应用市场") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(HugeIcons.Refresh01, "刷新")
                    }
                    IconButton(onClick = {
                        // 切换排序: downloads ↔ updated
                        val next = if (state.sort == MarketSort.DOWNLOADS) MarketSort.UPDATED else MarketSort.DOWNLOADS
                        vm.setSort(next)
                    }) {
                        Icon(HugeIcons.Sorting01, "排序")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize()) {
            ScrollableTabRow(
                selectedTabIndex = typeTabs.indexOfFirst { it.second == state.typeFilter }.coerceAtLeast(0),
                edgePadding = 8.dp,
                divider = {},
            ) {
                typeTabs.forEachIndexed { idx, (label, type) ->
                    Tab(
                        selected = idx == typeTabs.indexOfFirst { it.second == state.typeFilter }.coerceAtLeast(0),
                        onClick = { vm.setTypeFilter(type) },
                        text = { Text(label) },
                    )
                }
            }

            if (state.loading && state.entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.error != null && state.entries.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(state.error ?: "加载失败", color = MaterialTheme.colorScheme.error)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = innerPadding + PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.entries, key = { it.id }) { entry ->
                        MarketEntryCard(
                            entry = entry,
                            installed = installedMap[entry.id] != null,
                            onClick = { navController.navigate(Screen.MarketDetail(entry.id)) },
                        )
                    }
                    if (state.loading && state.entries.isNotEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                    if (!state.loading && state.entries.size >= state.total && state.entries.isNotEmpty()) {
                        item {
                            Text(
                                "全部 ${state.total} 个 · 已到底部",
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketEntryCard(entry: MarketEntry, installed: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!entry.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = entry.logoUrl,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                )
            } else {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(entry.type.first().uppercaseChar().toString(), style = MaterialTheme.typography.titleMedium)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(entry.title.ifBlank { entry.id }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (installed) {
                        FilterChip(
                            selected = true,
                            onClick = {},
                            label = { Text("已装", style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Text(
                    entry.description.ifBlank { entry.detail },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${entry.type} · 下载 ${entry.effectiveDownloadCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
