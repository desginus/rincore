package me.rerere.rikkahub.data.operit.importer


// ───【自研】岔路口计划·阶段3 — Operit MCP 导入器
// installConfig (mcpServers JSON) → RinCore McpServerConfig → Settings.mcpServers
// stdio: command/args → StdioTransportServer (env 丢弃 — RinCore 无 env 字段, 诚实降级)
// http/sse: url → StreamableHTTPServer / SseTransportServer
// ───────────────────────────────────────────────────────────────
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.SettingsStore
import kotlin.uuid.Uuid

object OperitMcpImporter {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 解析 Operit installConfig (mcpServers JSON) → McpServerConfig 列表 */
    fun parseInstallConfig(installConfig: String): List<McpServerConfig> {
        if (installConfig.isBlank()) return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(installConfig).jsonObject
            val servers = root["mcpServers"]?.jsonObject ?: return emptyList()
            servers.entries.mapNotNull { (name, element) ->
                val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
                val url = obj["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val command = obj["command"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val args = obj["args"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
                val type = obj["type"]?.jsonPrimitive?.content
                when {
                    command != null -> McpServerConfig.StdioTransportServer(
                        commonOptions = McpCommonOptions(name = name),
                        command = command,
                        args = args,
                    )
                    url != null -> if (type == "sse") {
                        McpServerConfig.SseTransportServer(
                            commonOptions = McpCommonOptions(name = name),
                            url = url,
                        )
                    } else {
                        McpServerConfig.StreamableHTTPServer(
                            commonOptions = McpCommonOptions(name = name),
                            url = url,
                        )
                    }
                    else -> null
                }
            }
        }.getOrDefault(emptyList())
    }

    /** 导入到 Settings.mcpServers (同名跳过); 返回新加入的 server ids */
    suspend fun applyImport(
        settingsStore: SettingsStore,
        configs: List<McpServerConfig>,
    ): List<Uuid> = withContext(Dispatchers.IO) {
        if (configs.isEmpty()) return@withContext emptyList()
        var added = emptyList<Uuid>()
        settingsStore.update { settings ->
            val existingNames = settings.mcpServers.map { it.commonOptions.name }.toSet()
            val toAdd = configs.filter { it.commonOptions.name !in existingNames }
            added = toAdd.map { it.id }
            if (toAdd.isEmpty()) settings else settings.copy(mcpServers = settings.mcpServers + toAdd)
        }
        added
    }

    /** 启用/停用已导入的 server (切换 McpCommonOptions.enable) */
    suspend fun setEnabled(
        settingsStore: SettingsStore,
        ids: List<Uuid>,
        enabled: Boolean,
    ) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        settingsStore.update { settings ->
            settings.copy(
                mcpServers = settings.mcpServers.map { cfg ->
                    if (cfg.id in ids) {
                        cfg.clone(commonOptions = cfg.commonOptions.copy(enable = enabled))
                    } else cfg
                },
            )
        }
    }

    /** 从 Settings.mcpServers 移除已导入的 server */
    suspend fun remove(
        settingsStore: SettingsStore,
        ids: List<Uuid>,
    ) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        settingsStore.update { settings ->
            settings.copy(mcpServers = settings.mcpServers.filter { it.id !in ids })
        }
    }
}
