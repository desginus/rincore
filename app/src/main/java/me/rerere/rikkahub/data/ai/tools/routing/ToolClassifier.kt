package me.rerere.rikkahub.data.ai.tools.routing

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Message
import me.rerere.ai.core.UIMessagePart
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.rikkahub.data.datastore.Settings

class ToolClassifier(
    private val providerManager: ProviderManager,
    private val settings: Settings,
) {
    companion object {
        val DEFAULT_PROMPT = """
你是一个工具分类助手。根据用户的任务意图，将工具分配到合适的场景分类中。

## 可用场景（树状层级，路径越深越精准）：

搜索           — 搜索网页、查资料、查新闻、商品搜索
  搜索/网页    — 搜索网页、查资料、查新闻
  搜索/购物    — 搜索商品、比价
文件           — 读写管理文件、压缩解压
  文件/浏览    — 列出目录、查看文件信息
  文件/读写    — 读取、写入文件
  文件/压缩    — 创建ZIP、解压
工作区         — 沙箱读写执行Shell命令
浏览器         — 打开网页、点击填表、截图提取
  浏览器/导航  — 打开网页、前进后退
  浏览器/交互  — 点击、输入、提交、滚动
  浏览器/提取  — 获取文本、DOM、截图
计算           — JS代码执行和物理力学计算
  计算/JS      — 执行JavaScript代码
  计算/物理    — 物理力学仿真计算
图表           — 生成各类图表
推理           — 深度推理、思维模型
调度           — 定时任务、日历事件
媒体           — 音频视频播放控制
设备           — 剪贴板、通知、定位、屏幕时间
生成           — 二维码、文字转语音
部署           — 部署HTML网页
对话           — 子代理、记忆、时间

## 分类规则：
- 按用户需求分类，不是按技术来源分类
- 忽略工具名前缀（如 mcp__xxx），只看功能
- 不确定时选上层场景（如不确定是"计算/JS"还是"计算/物理"就选"计算"）
- 路径格式：一级场景 或 一级/子场景

## 任务：
对以下工具列表进行分类，返回 JSON 格式。
格式：{"tool_name":"场景路径", ...}

工具列表：
{{TOOLS}}
""".trimIndent()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 构建分类提示词，将工具列表嵌入模板
     */
    fun buildPrompt(tools: List<Pair<String, String>>, customPrompt: String = ""): String {
        val prompt = if (customPrompt.isNotBlank()) customPrompt else DEFAULT_PROMPT
        val toolList = tools.joinToString("\n") { (name, desc) ->
            "- $name: ${desc.take(200)}"
        }
        return prompt.replace("{{TOOLS}}", toolList)
    }

    /**
     * 调用 AI 模型分类工具
     * @param tools 待分类工具 (名称 -> 描述)
     * @return 分类结果 (工具名 -> 域路径)
     */
    suspend fun classify(
        tools: List<Pair<String, String>>,
        model: Model,
    ): Result<Map<String, String>> {
        val prompt = buildPrompt(tools, settings.classifierPrompt)
        val messages = listOf(
            Message(role = "user", content = listOf(UIMessagePart.Text(prompt)))
        )

        return try {
            val provider = providerManager.getProvider(model)
                ?: return Result.failure(Exception("找不到模型 ${model.displayName}"))

            val providerSettings = settings.providers.find { it.id == model.providerId }
                ?: return Result.failure(Exception("找不到提供商配置"))

            val response = provider.textGeneration(
                providerSetting = providerSettings,
                model = model,
                messages = messages,
                tools = emptyList(),
                generationConfig = TextGenerationParams(
                    temperature = 0.1f,
                    maxTokens = 4096,
                ),
            )

            // 从流中收集所有响应
            var fullText = ""
            response.collect { chunk ->
                chunk.choices.forEach { choice ->
                    choice.delta.content?.let { fullText += it }
                }
            }

            // 提取 JSON
            val jsonStr = extractJson(fullText)
            val result = parseClassificationResult(jsonStr)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end < 0) return "{}"
        return text.substring(start, end + 1)
    }

    private fun parseClassificationResult(jsonStr: String): Map<String, String> {
        try {
            val obj = json.parseToJsonElement(jsonStr).jsonObject
            return obj.mapValues { it.value.jsonPrimitive.content }
        } catch (e: Exception) {
            return emptyMap()
        }
    }
}
