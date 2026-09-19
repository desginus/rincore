package me.rerere.rikkahub.data.operit.runtime


// ───【自研】岔路口计划·阶段2 — Operit 脚本工具提供器
// 从已启用脚本生成 operit__<包>__<工具> 工具 (归「插件」域)
// ───────────────────────────────────────────────────────────────
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.operit.market.InstalledPackage
import me.rerere.rikkahub.data.operit.market.InstalledPackageStore
import java.io.File

class OperitToolProvider(
    private val store: InstalledPackageStore,
    private val runtime: OperitScriptRuntime,
) {
    data class EnabledScript(
        val pkg: InstalledPackage,
        val metadata: ScriptMetadata,
        val file: File,
    )

    @Volatile
    private var enabledScripts: List<EnabledScript> = emptyList()

    /** 刷新已启用脚本缓存 (安装/启用/停用/卸载/启动时调用) */
    suspend fun refresh() {
        val installed = store.listInstalled()
        enabledScripts = installed
            .filter { it.enabled && it.type == "script" }
            .mapNotNull { pkg ->
                val file = File(pkg.installPath)
                if (!file.exists() || !file.name.endsWith(".js")) return@mapNotNull null
                val meta = runCatching { ScriptMetadataParser.parse(file.readText()) }.getOrNull()
                    ?: return@mapNotNull null
                EnabledScript(pkg, meta, file)
            }
    }

    /** 生成脚本工具列表 (同步, 读内存缓存 — 供 buildAssistantToolPool 调用) */
    fun createScriptTools(): List<Tool> {
        return enabledScripts.flatMap { es ->
            es.metadata.tools.mapNotNull { decl ->
                val fnName = decl.name
                if (fnName.isBlank()) return@mapNotNull null
                val pkgKey = es.pkg.runtimePackageId?.substringAfterLast('.')
                    ?: es.metadata.name.ifBlank { es.pkg.entryId }
                val toolName = "operit__${sanitize(pkgKey)}__${sanitize(fnName)}"
                Tool(
                    name = toolName,
                    description = buildString {
                        append(ScriptMetadataParser.pickText(decl.description))
                        if (isEmpty()) append(ScriptMetadataParser.pickText(es.metadata.description))
                    }.ifBlank { "Operit 脚本工具: $fnName (${es.pkg.title})" },
                    parameters = { schemaFrom(decl) },
                    execute = { args ->
                        val result = runtime.executeTool(es.file, fnName, args)
                        val text = result.getOrElse { e ->
                            buildJsonObject {
                                put("success", kotlinx.serialization.json.JsonPrimitive(false))
                                put("message", kotlinx.serialization.json.JsonPrimitive("脚本执行失败: ${e.message ?: e.toString()}"))
                            }
                        }
                        listOf(UIMessagePart.Text(text.toString()))
                    },
                )
            }
        }
    }

    private fun sanitize(s: String): String = s.replace(Regex("[^A-Za-z0-9_]"), "_").take(48)

    private fun schemaFrom(decl: ScriptToolDecl): InputSchema? {
        if (decl.parameters.isEmpty()) return null
        val props: JsonObject = buildJsonObject {
            decl.parameters.forEach { p ->
                if (p.name.isBlank()) return@forEach
                put(p.name, buildJsonObject {
                    put("type", p.type.ifBlank { "string" })
                    val d = ScriptMetadataParser.pickText(p.description)
                    if (d.isNotBlank()) put("description", d)
                })
            }
        }
        val required = decl.parameters.filter { it.required }.map { it.name }
        return InputSchema.Obj(properties = props, required = required.ifEmpty { null })
    }
}
