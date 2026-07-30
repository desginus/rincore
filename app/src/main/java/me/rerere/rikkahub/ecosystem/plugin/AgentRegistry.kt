package me.rerere.rikkahub.ecosystem.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Agent 编排定义 — 对接 .agents/ 子目录和 subagent_dispatch 工具。
 *
 * 格式: .agents/<name>.json
 * {
 *   "name": "web-researcher",
 *   "description": "搜索互联网并返回结构化报告",
 *   "systemPrompt": "你是研究助手...",
 *   "tools": ["web_search", "scrape_web", "workspace_shell"],
 *   "maxSteps": 5,
 *   "model": "auto"
 * }
 *
 * 后续可对接子代理调度器 (subagent_dispatch) 实现多代理协作。
 */
@Serializable
data class AgentDefinition(
    val name: String = "",
    val description: String = "",
    val systemPrompt: String = "",
    val tools: List<String> = emptyList(),
    val maxSteps: Int = 5,
    val model: String = "auto",
    val inheritsMemory: Boolean = false,
    val inheritsContext: Boolean = true,
)

object AgentRegistry {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val _agents = mutableMapOf<String, AgentDefinition>()

    fun loadFromDir(dir: File) {
        val agentsDir = File(dir, ".agents")
        if (!agentsDir.isDirectory) return
        agentsDir.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.forEach { file ->
                try {
                    val agent = json.decodeFromString<AgentDefinition>(file.readText())
                    _agents[agent.name] = agent
                } catch (_: Exception) {}
            }
    }

    fun listAgents(): List<AgentDefinition> = _agents.values.toList()

    fun getAgent(name: String): AgentDefinition? = _agents[name]

    fun register(agent: AgentDefinition) {
        _agents[agent.name] = agent
    }
}
