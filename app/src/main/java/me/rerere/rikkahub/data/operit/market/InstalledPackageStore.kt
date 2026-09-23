/* 【域 K·生态扩展】 | 地图: docs/APP_MAP.md §K */
package me.rerere.rikkahub.data.operit.market


/* ───【自研】岔路口计划·阶段1 — 已安装插件记录 (独立 DataStore, 不污染主库)
 * 来源: RinCore 自研新增
 * ───────────────────────────────────────────────────────────────*/
import android.content.Context
import java.io.File
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class InstalledPackage(
    val entryId: String,
    val type: String,                  // script / package / skill / mcp
    val title: String,
    val version: String,
    val assetId: String,
    val fileName: String,              // 存储文件名 (script: .js; package: .toolpkg 解压目录名)
    val installPath: String,           // 绝对路径
    val formatVer: String = "",        // script_v2 / toolpkg_v2 / skill_v2 / mcp_v2
    val sha256: String = "",
    val installedAt: Long = System.currentTimeMillis(),
    val enabled: Boolean = false,       // 阶段2 才支持真正启用; 阶段1 默认 false
    val runtimePackageId: String? = null,
    // v4.5.30 阶段3: 类型特定附加数据
    //   skill: 导入的技能名列表 (JSON array 字符串)
    //   mcp:   导入的 server id 列表 (JSON array 字符串)
    val extraJson: String = "",
    val sourceUrl: String = "",          // skill/mcp 的 source (GitHub repo url)
)

private val Context.operitInstalledDataStore: DataStore<Preferences> by preferencesDataStore(name = "operit_installed")
private val INSTALLED_KEY = stringPreferencesKey("installed_packages_json")
// v4.6.3: 内置包播种记忆 — 记录"已播种过"的 entryId 集合,
// 用户卸载内置包后不再复活 (播种循环只播不在集合中的)
private val SEEDED_KEY = stringPreferencesKey("seeded_builtin_entries_json")

class InstalledPackageStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val dir = File(context.filesDir, "operit_packages").apply { mkdirs() }

    /** 各类型独立子目录, 阶段2 运行时从这里加载 (type 消毒防路径穿越) */
    fun storageDir(type: String): File {
        val safe = type.lowercase().filter { it.isLetterOrDigit() }.ifBlank { "misc" }.take(24)
        return File(dir, safe).apply { mkdirs() }
    }

    val installedFlow: Flow<List<InstalledPackage>> = context.operitInstalledDataStore.data.map { p ->
        p[INSTALLED_KEY]?.let { json.decodeFromString<List<InstalledPackage>>(it) } ?: emptyList()
    }

    suspend fun listInstalled(): List<InstalledPackage> = withContext(Dispatchers.IO) {
        installedFlow.first()
    }

    suspend fun getInstalled(entryId: String): InstalledPackage? = listInstalled().firstOrNull { it.entryId == entryId }

    suspend fun upsert(pkg: InstalledPackage) = withContext(Dispatchers.IO) {
        context.operitInstalledDataStore.edit { p ->
            val list = p[INSTALLED_KEY]?.let { json.decodeFromString<List<InstalledPackage>>(it) } ?: emptyList()
            val updated = list.filterNot { it.entryId == pkg.entryId } + pkg
            p[INSTALLED_KEY] = json.encodeToString(updated)
        }
    }

    suspend fun remove(entryId: String) = withContext(Dispatchers.IO) {
        context.operitInstalledDataStore.edit { p ->
            val list = p[INSTALLED_KEY]?.let { json.decodeFromString<List<InstalledPackage>>(it) } ?: emptyList()
            p[INSTALLED_KEY] = json.encodeToString(list.filterNot { it.entryId == entryId })
        }
    }

    suspend fun setEnabled(entryId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        context.operitInstalledDataStore.edit { p ->
            val list = p[INSTALLED_KEY]?.let { json.decodeFromString<List<InstalledPackage>>(it) } ?: emptyList()
            val updated = list.map { if (it.entryId == entryId) it.copy(enabled = enabled) else it }
            p[INSTALLED_KEY] = json.encodeToString(updated)
        }
    }

    // ── v4.6.3: 内置包播种记忆 ────────────────────────────────
    suspend fun getSeededEntries(): Set<String> = withContext(Dispatchers.IO) {
        context.operitInstalledDataStore.data.first()[SEEDED_KEY]?.let { raw ->
            runCatching { json.decodeFromString<Set<String>>(raw) }.getOrDefault(emptySet())
        } ?: emptySet()
    }

    suspend fun addSeededEntries(ids: Collection<String>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        context.operitInstalledDataStore.edit { p ->
            val current = p[SEEDED_KEY]?.let { raw ->
                runCatching { json.decodeFromString<Set<String>>(raw) }.getOrDefault(emptySet())
            } ?: emptySet()
            p[SEEDED_KEY] = json.encodeToString(current + ids)
        }
    }
}
