/* 【域 B·AI 传输】ImageBudget.kt — 从 GenerationHandler 拆出 (4.8.13 重构)
 * 职责: 图片预算体系 — GLM 网关判定 + 数量/字节预算 + 持久降级标记。
 *       五轮补丁净效果整合版 (v4.3.6 引入 → v4.3.8 → v4.3.10 持久标记
 *       → v4.5.19 收紧 → v4.7.24 精细化, 各轮语义已合并为统一逻辑)。
 * 常用改动: GLM 判定条件 → isGlmGatewayModel; 预算参数 → 本文件常量;
 *           标记语义 → applyImageBudgetMarking (消费方: ChatCompletionsAPI.applyImageMarkers)
 * 问题定位: 图片发不出去 / too_many_images / 请求体超 TCP payload → 本文件
 * 基线: 自研 | 地图: docs/APP_MAP.md §B | 历史: .claude/skills/rincore-bug-record (B118 家族)
 * ───────────────────────────────────────────────────────────────*/

package me.rerere.rikkahub.data.ai

import android.util.Log
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

private const val TAG = "ImageBudget"

/**
 * v4.7.24: 图片数量限制 (用户定版) — 仅 GLM 网关 (GLM 字段 且 OpenCode/CC 字段,
 * 1+1 判定) 限 6 张 (GLM 网关硬限 8 张 [too_many_images], 6 留安全余量);
 * 其他所有模型无数量限制 (放弃原全局 8 张预算)。
 */
internal const val GLM_GATEWAY_IMAGE_LIMIT = 6

/**
 * v4.5.19: 单张/总量预算从 16M/64M 收紧到 5MiB —
 * Console Go 实测: 4 张 ~1.84M 字符的图被其按 ~47x 系数计
 * (86.6MB/张, 总 330MiB > 256MiB 预算被拒 "Request exceeds TCP
 * payload budget")。5MiB 字符在 45x-64x 系数下均 < 256MiB:
 * 4x1.84M 场景自动降级最旧 2 张 → 保留 2 张 (3.68M → 166-235MiB)。
 * 降级为持久标记 (budget_dropped), 请求中替换为路径占位,
 * 模型可经 read_image 按需读取; UI 内容零改动。
 */
internal const val IMAGE_BUDGET_SINGLE_BYTES = 5L * 1024 * 1024
internal const val IMAGE_BUDGET_TOTAL_BYTES = 5L * 1024 * 1024

/**
 * v4.7.24: GLM 网关判定 (图片数量限制的适用范围)。
 * 用户定版语义: "模型字段中含有 GLM 与 OpenCode 或 Command Code 这样的字段,
 * 达成 1+1" — 即 GLM 族 且 经 OpenCode/CC 网关通道, 才限 6 张;
 * 其余模型无数量限制。判定在 modelId / provider 名称 / baseUrl 三处现场任意
 * 命中即可 (用户对 provider 的命名习惯各异, 宽匹配防漏)。
 */
internal fun isGlmGatewayModel(model: Model, provider: ProviderSetting): Boolean {
    val haystack = buildString {
        append(model.modelId.lowercase()).append(' ')
        append(model.displayName.lowercase()).append(' ')
        append(provider.name.lowercase()).append(' ')
        if (provider is ProviderSetting.OpenAI) {
            append(provider.baseUrl.lowercase())
        }
    }
    val hasGlm = haystack.contains("glm") || haystack.contains("bigmodel") || haystack.contains("zhipu")
    val hasGateway = haystack.contains("opencode") || haystack.contains("opencode.ai") ||
        haystack.contains("command") || haystack.contains("claude")
    return hasGlm && hasGateway
}

/**
 * v4.3.10 (BUG15 v2) + v4.3.11 (BUG17): 图片预算判定+持久标记 — 未标记图中从
 * 最新往旧保留 countLimit 张 (count/size 预算), 超额的写 budget_dropped 标记。
 * 标记随消息落盘持久化 (重启不丢), 降级不可逆 → 请求前缀在已降级位置
 * 恒定 (消除 v4.3.6 "最近 8 张" 滚动重算造成的每轮前缀断裂)。
 * 只改 Image.metadata, 内容零改动, UI 渲染不受影响; 请求构造时
 * ChatCompletionsAPI.applyImageMarkers 消费标记替换为占位文本。
 *
 * v4.3.11 (BUG17): Slot key=(mi, pi, oi) — pi 永远是真实 part 索引 (普通图
 * oi=-1, Tool.output 图 oi>=0 且 pi=Tool 的 part 索引)。v4.3.10 曾用 pi=-1
 * 哨兵存 Tool 图, imageAt 先 parts[pi] 越界 → 用户发 PDF 即崩溃。统一真实
 * 索引后 imageAt 先走 oi 分支, 无 -1 索引; 再加 getOrNull 边界守卫双保险。
 */
internal fun applyImageBudgetMarking(messages: List<UIMessage>, countLimit: Int): List<UIMessage> {
    data class Slot(val key: Triple<Int, Int, Int>, val url: String, val bytes: Long)
    val slots = mutableListOf<Slot>()
    val imageAt: (Int, Int, Int) -> UIMessagePart.Image? = { mi, pi, oi ->
        val p = messages.getOrNull(mi)?.parts?.getOrNull(pi)
        when {
            p == null -> null
            oi < 0 -> p as? UIMessagePart.Image
            else -> (p as? UIMessagePart.Tool)?.output?.getOrNull(oi) as? UIMessagePart.Image
        }
    }
    messages.forEachIndexed { mi, msg ->
        msg.parts.forEachIndexed { pi, part ->
            when {
                part is UIMessagePart.Image && part.metadata?.get("budget_dropped") != null -> {}
                part is UIMessagePart.Image -> slots.add(Slot(Triple(mi, pi, -1), part.url, 0L))
                part is UIMessagePart.Tool -> part.output.forEachIndexed { oi, op ->
                    if (op is UIMessagePart.Image && op.metadata?.get("budget_dropped") == null) {
                        slots.add(Slot(Triple(mi, pi, oi), op.url, 0L))
                    }
                }
                else -> {}
            }
        }
    }
    // 字节估算: data URL 按字符数, 本地文件按大小, 公网 URL 仅计张数
    val sized = slots.map { s ->
        val img = imageAt(s.key.first, s.key.second, s.key.third)
        val bytes = when {
            img == null -> 0L
            img.url.startsWith("data:") -> img.url.length.toLong()
            img.url.startsWith("file://") -> runCatching {
                java.io.File(img.url.removePrefix("file://")).length()
            }.getOrDefault(0L)
            else -> 0L
        }
        s.copy(bytes = bytes)
    }
    if (sized.size <= countLimit && sized.sumOf { it.bytes } <= IMAGE_BUDGET_TOTAL_BYTES) return messages
    val kept = mutableSetOf<Triple<Int, Int, Int>>()
    var total = 0L
    for (s in sized.asReversed()) {
        if (kept.size >= countLimit) break
        if (s.bytes > IMAGE_BUDGET_SINGLE_BYTES) continue
        if (total + s.bytes > IMAGE_BUDGET_TOTAL_BYTES) continue
        kept.add(s.key)
        total += s.bytes
    }
    val dropped = sized.filter { it.key !in kept }
    if (dropped.isEmpty()) return messages
    Log.w(TAG, "image budget: dropping ${dropped.size} older images (kept ${kept.size}, ${dropped.size + kept.size} total)")
    val result = messages.mapIndexed { mi, msg ->
        if (dropped.none { it.key.first == mi }) msg else msg.copy(parts = msg.parts.mapIndexed { pi, part ->
            when {
                part is UIMessagePart.Image && dropped.any { it.key.first == mi && it.key.second == pi && it.key.third == -1 } ->
                    part.copy(metadata = kotlinx.serialization.json.buildJsonObject {
                        (part.metadata ?: kotlinx.serialization.json.JsonObject(emptyMap())).forEach { (k, v) -> put(k, v) }
                        put("budget_dropped", kotlinx.serialization.json.JsonPrimitive("true"))
                    })
                part is UIMessagePart.Tool -> part.copy(output = part.output.mapIndexed { oi, op ->
                    if (op is UIMessagePart.Image && dropped.any { it.key.first == mi && it.key.second == pi && it.key.third == oi }) {
                        op.copy(metadata = kotlinx.serialization.json.buildJsonObject {
                            (op.metadata ?: kotlinx.serialization.json.JsonObject(emptyMap())).forEach { (k, v) -> put(k, v) }
                            put("budget_dropped", kotlinx.serialization.json.JsonPrimitive("true"))
                        })
                    } else op
                })
                else -> part
            }
        })
    }
    Log.i(TAG, "image budget: permanent downgrade marks written (${dropped.size} images, persisted with message store)")
    return result
}
