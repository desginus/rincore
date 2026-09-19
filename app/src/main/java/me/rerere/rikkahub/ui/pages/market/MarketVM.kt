package me.rerere.rikkahub.ui.pages.market


/* ───【自研】岔路口计划·阶段1 — 商城 ViewModel
 * 来源: RinCore 自研新增
 * ───────────────────────────────────────────────────────────────*/
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.operit.market.InstalledPackage
import me.rerere.rikkahub.data.operit.market.MarketEntry
import me.rerere.rikkahub.data.operit.market.MarketInstallService
import me.rerere.rikkahub.data.operit.market.MarketListFilter
import me.rerere.rikkahub.data.operit.market.MarketManifest
import me.rerere.rikkahub.data.operit.market.MarketRepository
import me.rerere.rikkahub.data.operit.market.MarketSort

data class MarketListUiState(
    val loading: Boolean = false,
    val entries: List<MarketEntry> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 100,
    val error: String? = null,
    val typeFilter: String? = null,        // null = 全部
    val sort: MarketSort = MarketSort.DOWNLOADS,
    val manifest: MarketManifest? = null,
)

data class MarketDetailUiState(
    val loading: Boolean = false,
    val entry: MarketEntry? = null,
    val installed: InstalledPackage? = null,
    val installing: Boolean = false,
    val installProgress: Float = 0f,       // 0~1
    val message: String? = null,
)

class MarketVM(
    private val repository: MarketRepository,
    private val installService: MarketInstallService,
    private val installedStore: me.rerere.rikkahub.data.operit.market.InstalledPackageStore,
    private val operitToolProvider: me.rerere.rikkahub.data.operit.runtime.OperitToolProvider,
    private val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore,
) : ViewModel() {

    private val _listState = MutableStateFlow(MarketListUiState(loading = true))
    val listState: StateFlow<MarketListUiState> = _listState.asStateFlow()

    private val _detailState = MutableStateFlow(MarketDetailUiState())
    val detailState: StateFlow<MarketDetailUiState> = _detailState.asStateFlow()

    private val _installedMap = MutableStateFlow<Map<String, InstalledPackage>>(emptyMap())
    val installedMap: StateFlow<Map<String, InstalledPackage>> = _installedMap.asStateFlow()

    init {
        installedStore.installedFlow
            .map { list -> list.associateBy { it.entryId } }
            .onEach { _installedMap.value = it }
            .launchIn(viewModelScope)
        loadManifest()
        loadList()
    }

    fun setTypeFilter(type: String?) {
        _listState.value = _listState.value.copy(typeFilter = type, page = 1, loading = true, error = null)
        loadList()
    }

    fun setSort(sort: MarketSort) {
        _listState.value = _listState.value.copy(sort = sort, page = 1, loading = true, error = null)
        loadList()
    }

    fun loadNextPage() {
        val s = _listState.value
        if (s.loading) return
        val loaded = s.entries.size
        if (loaded >= s.total) return
        _listState.value = s.copy(page = s.page + 1, loading = true)
        loadList(append = true)
    }

    fun refresh() {
        repository.invalidateAll()
        _listState.value = _listState.value.copy(loading = true, page = 1, error = null)
        loadManifest()
        loadList()
    }

    private fun loadManifest() {
        viewModelScope.launch {
            repository.getManifest().onSuccess { m ->
                _listState.value = _listState.value.copy(manifest = m)
            }
        }
    }

    private fun loadList(append: Boolean = false) {
        viewModelScope.launch {
            val s = _listState.value
            val filter: MarketListFilter = when (s.typeFilter) {
                null -> MarketListFilter.All(s.sort)
                else -> MarketListFilter.ByType(s.typeFilter, s.sort)
            }
            repository.getListPage(filter, s.page).onSuccess { resp ->
                val merged = if (append) s.entries + resp.items else resp.items
                _listState.value = s.copy(
                    loading = false,
                    entries = merged.distinctBy { it.id },
                    total = resp.total,
                    pageSize = resp.pageSize,
                    error = null,
                )
            }.onFailure { e ->
                _listState.value = s.copy(loading = false, error = e.message ?: "load failed")
            }
        }
    }

    fun loadDetail(entryId: String) {
        _detailState.value = MarketDetailUiState(loading = true)
        viewModelScope.launch {
            repository.getEntry(entryId).onSuccess { entry ->
                _detailState.value = MarketDetailUiState(
                    loading = false,
                    entry = entry,
                    installed = _installedMap.value[entryId],
                )
            }.onFailure { e ->
                _detailState.value = MarketDetailUiState(loading = false, message = e.message)
            }
        }
    }

    fun install(entry: MarketEntry) {
        val current = _detailState.value
        if (current.installing) return
        _detailState.value = current.copy(installing = true, installProgress = 0f, message = null)
        viewModelScope.launch {
            val installResult = runCatching {
                installService.install(entry) { d, t ->
                    _detailState.value = _detailState.value.copy(
                        installProgress = if (t > 0) (d.toFloat() / t).coerceIn(0f, 1f) else 0f,
                    )
                }
            }.getOrElse { e ->
                MarketInstallService.InstallResult.Failure("安装异常: ${e.message ?: e}", e)
            }
            when (val r = installResult) {
                is MarketInstallService.InstallResult.Success -> {
                    _detailState.value = _detailState.value.copy(
                        installing = false,
                        installed = r.installed,
                        installProgress = 1f,
                        message = "已安装",
                    )
                }
                is MarketInstallService.InstallResult.AlreadyInstalled -> {
                    _detailState.value = _detailState.value.copy(
                        installing = false,
                        installed = r.installed,
                        message = "已是该版本",
                    )
                }
                is MarketInstallService.InstallResult.Failure -> {
                    _detailState.value = _detailState.value.copy(
                        installing = false,
                        message = "安装失败: ${r.reason}",
                    )
                }
            }
        }
    }

    fun uninstall(entryId: String) {
        viewModelScope.launch {
            if (installService.uninstall(entryId)) {
                runCatching { operitToolProvider.refresh() }
                _detailState.value = _detailState.value.copy(
                    installed = null,
                    message = "已卸载",
                )
            }
        }
    }

    /**
     * v4.5.29/30 阶段2/3: 启用/停用 (按类型分发)
     *   script: 工具注册/移除「插件」域
     *   mcp:    切换 mcpServers 里的 enable 状态
     *   skill:  导入即用, 无开关
     */
    fun setScriptEnabled(entryId: String, enabled: Boolean) {
        viewModelScope.launch {
            val installed = _detailState.value.installed ?: installedStore.getInstalled(entryId)
            val type = installed?.type ?: "script"
            runCatching {
                when (type) {
                    "mcp" -> {
                        val ids = parseUuids(installed?.extraJson.orEmpty())
                        me.rerere.rikkahub.data.operit.importer.OperitMcpImporter.setEnabled(settingsStore, ids, enabled)
                        installedStore.setEnabled(entryId, enabled)
                    }
                    else -> {
                        installedStore.setEnabled(entryId, enabled)
                        operitToolProvider.refresh()
                    }
                }
            }
            _detailState.value = _detailState.value.copy(
                installed = _detailState.value.installed?.copy(enabled = enabled),
                message = if (enabled) "已启用" else "已停用",
            )
        }
    }

    private fun parseUuids(extraJson: String): List<kotlin.uuid.Uuid> = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(extraJson)
            .let { el -> (el as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.content } ?: emptyList() }
            .mapNotNull { runCatching { kotlin.uuid.Uuid.parse(it) }.getOrNull() }
    }.getOrDefault(emptyList())
}
