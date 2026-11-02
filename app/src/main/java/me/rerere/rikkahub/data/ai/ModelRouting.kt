/* 【域 C·工具系统】 */
package me.rerere.rikkahub.data.ai

import android.util.Log
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelType
import kotlin.uuid.Uuid

/* ───【自研】ModelRouting.kt — 「快速/极致」池的能力准入与选模（v5.3.35 新增）
 *
 * ═══ 为什么需要这个文件（用户实测事故的根本原因）═══
 *
 * 用户报告：「原本用的是 DeepSeek，中途它路由到了智谱，然后智谱的这个模型就无法正常
 * 工作了。具体表现为：思考一段之后，把本该用来调用工具的工具调用直接当成了正文发给用户。」
 *
 * 代码级根因链（每一环都已核实）：
 *   ① `ChatCompletionsAPI:1070` 只在 `model.abilities.contains(ModelAbility.TOOL)`
 *      且 `tools.isNotEmpty()` 时才把 `tools` 参数放进请求体；
 *   ② 而「快速/极致」池的选模（v5.3.34 的 `ModelLatencyStats.fastest`）**只看首字速度，
 *      完全不看 abilities，也不看 contextLength**；
 *   ③ 于是池内一个**未声明 TOOL 能力**的模型可以因为「上次首字快」而被选中；
 *   ④ 它拿不到任何工具定义，却从对话历史里看到过工具调用的痕迹 →
 *      **只能把工具调用模仿成普通文本吐出来** = 用户看到的那一幕。
 *
 * ═══ 设计原则：能力是准入门槛，速度只在合格集合内排序 ═══
 *
 * 这是业界一致做法（OpenRouter 实际 API 字段可直接印证）：
 *   · 每个模型带 `supported_parameters`（含 `tool_choice` / `tools`）与 `context_length`；
 *   · 它的 `:nitro`（最快）路由是在**满足所需参数**的模型集合里排序，而不是全网比快。
 *
 * 所以本文件只做三件事：
 *   1. `ineligibility()` —— 判定某模型能否胜任**当前这一轮**的需求（工具 / 上下文）；
 *   2. `score()` —— 在合格模型里给出排序分（历史首字 + 失败罚分）；
 *   3. `pick()` —— 会话粘性优先，速度显著更快才切换。
 *
 * ═══ 隔离契约（与用户「坏了不能拖累主链路」一致）═══
 *   · 全部函数纯计算、不抛异常、无 IO；
 *   · 任何不合格 / 无候选都只是**返回普通模型**（调用方兜底），绝不影响正常对话。
 * ───────────────────────────────────────────────────────────────*/

object ModelRouting {

    private const val TAG = "ModelRouting"

    /** 失败一次等价于多等 3 秒 —— 让「快但不可靠」的模型自然排到后面 */
    private const val FAILURE_PENALTY_MS = 3_000L

    /** 没有任何首字样本时的保守默认值（不惩罚未知模型，也不让它排到已实测的前面） */
    private const val UNKNOWN_TTFB_MS = 2_000L

    /** 粘性容忍度：粘性模型不慢于最优 1.5 倍就继续用（对话连续性 > 微小速度差） */
    private const val STICKY_TOLERANCE_NUM = 3L
    private const val STICKY_TOLERANCE_DEN = 2L

    /**
     * 该模型能否胜任这一轮；返回 `null` 表示合格，否则返回**人话原因**（用于日志/UI 提示）。
     *
     * @param requireTools 本轮是否需要工具调用。**保守取 true** —— 助手的 `localTools`
     *   默认就含 `TimeInfo`，而模型一旦拿不到工具定义又看到历史里的工具痕迹，
     *   就会重现「把工具调用当正文」的那一幕。宁可少用快速池，也不要出一个坏的轮次。
     * @param estimatedTokens 当前上下文的粗略 token 估算（0 = 未知，跳过该检查）
     */
    fun ineligibility(
        model: Model,
        requireTools: Boolean,
        estimatedTokens: Int,
    ): String? {
        if (model.type != ModelType.CHAT) return "非对话模型（${model.type}）"
        if (!model.outputModalities.contains(Modality.TEXT)) return "不支持文本输出"

        // ★核心：这一条直接对应「工具调用被当正文」的事故
        if (requireTools && !model.abilities.contains(ModelAbility.TOOL)) {
            return "未声明工具能力（TOOL）—— 请求不会带 tools 定义，模型会把工具调用写成正文"
        }

        // 上下文装不下 → 历史会被截断/忽略 → 「无法正确理解上下文」
        if (estimatedTokens > 0 && model.contextLength in 1 until estimatedTokens) {
            return "上下文窗口不足（${model.contextLength} < 当前约 ${estimatedTokens} tokens）"
        }
        return null
    }

    /** 路由排序分（越小越优）：历史首字 + 失败罚分 */
    fun score(model: Model): Long {
        val ttfb = ModelLatencyStats.average(model.id) ?: UNKNOWN_TTFB_MS
        val fails = ModelLatencyStats.failureCount(model.id)
        return ttfb + fails * FAILURE_PENALTY_MS
    }

    /**
     * 在**已通过准入**的候选里选一个。
     *
     * 顺序：
     *   1. 只有一个 → 就是它；
     *   2. 有会话粘性模型且在候选内、分数不高于最优 1.5 倍 → **继续用它**（不换脑子）；
     *   3. 否则取分数最优（最快且没失败过）。
     */
    fun pick(eligible: List<Model>, conversationId: String?): Model? {
        if (eligible.isEmpty()) return null
        if (eligible.size == 1) return eligible.first()

        val best = eligible.minByOrNull { score(it) } ?: return null
        val stickyId: Uuid? = conversationId?.let {
            runCatching { ModelLatencyStats.stickyOf(it) }.getOrNull()
        } ?: return best

        val sticky = eligible.firstOrNull { it.id == stickyId } ?: return best
        val stickyScore = score(sticky)
        val allowed = score(best) * STICKY_TOLERANCE_NUM / STICKY_TOLERANCE_DEN
        return if (stickyScore <= allowed) {
            runCatching { Log.d(TAG, "pick sticky=${sticky.displayName} score=$stickyScore (best=${score(best)})") }
            sticky
        } else {
            runCatching { Log.d(TAG, "pick switch ${sticky.displayName}($stickyScore) -> ${best.displayName}(${score(best)})") }
            best
        }
    }

    /** 过滤出合格候选，并把不合格原因打日志（排查「为什么没用快速池」时看这一行） */
    fun eligibleModels(
        models: List<Model>,
        requireTools: Boolean,
        estimatedTokens: Int,
    ): List<Model> {
        if (models.isEmpty()) return emptyList()
        val result = ArrayList<Model>(models.size)
        for (m in models) {
            val reason = ineligibility(m, requireTools, estimatedTokens)
            if (reason == null) {
                result.add(m)
            } else {
                runCatching { Log.w(TAG, "skip ${m.displayName}: $reason") }
            }
        }
        return result
    }
}
