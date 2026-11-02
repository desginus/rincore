/* 【域 C·工具系统】 */
package me.rerere.rikkahub.data.ai

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/* ───【自研】ModelLatencyStats.kt — 模型首字延迟统计（v5.3.34 新增）
 *
 * ★为什么要有这个文件（回答「快速/极致池是不是选对了模型」）：
 *
 * 旧实现 ModelRacer 的"竞速"测的是 **`GET {baseUrl}/models`** 这个元数据端点，
 * 而不是模型本身的推理速度。由此产生三个结构性错误：
 *   ① 池内多个模型若同属一个 provider（同一 baseUrl）→ 多个探针打**同一个 URL**，
 *      谁先返回纯属竞态噪声 →「同一 baseUrl 内选最快」**根本不成立**；
 *   ② 测到的是「连接建立 + 元数据端点首包」，与真正 chat/completions 的
 *      首 token 延迟（含排队、prefill、模型大小）**不是同一个量**；
 *   ③ 1.5s 同步超时 + 移动网 TLS 664ms~1.9s → 实测常常**全部超时** → 回退池首，
 *      等于「竞速了但没起作用」，还白白在首字前多等一段。
 *
 * ★正确做法（与 LiteLLM 的 latency-based-routing 同思路，只是搬到客户端）：
 *   用**真实请求的首字耗时**做统计，按模型聚合，选历史最快。
 *   · 零额外等待（不需要在发送前打任何探针）
 *   · 是真正的「模型速度」（包含 provider 排队与模型 prefill）
 *   · 新模型没有样本 → 返回 null，调用方用池首（跑一次后就有数据）
 *
 * ★隔离契约（用户定案：这功能坏了不能影响别的）：
 *   本文件不抛异常、不阻塞、不持有 Context；任何调用失败都只是「少一条样本」。
 *   统计仅存进程内存 —— 重启后重新学习（通常 3~5 轮对话即收敛），
 *   刻意不落盘：避免 DataStore 迁移风险，且网络环境变化后旧样本本来就不该长期有效。
 * ───────────────────────────────────────────────────────────────*/

object ModelLatencyStats {

    private const val TAG = "ModelLatency"

    /** 每个模型保留最近 N 次样本 —— 网络会变，旧样本不该永远主导判断 */
    private const val MAX_SAMPLES = 5

    /** 样本数达到这个值才认为「可信」（1 次可能撞上冷启动，不代表稳态） */
    private const val MIN_TRUSTED_SAMPLES = 1

    /** modelId → 最近若干次首字耗时（ms，插入顺序即时间顺序） */
    private val samples = ConcurrentHashMap<String, ArrayDeque<Long>>()

    /**
     * 记录一次真实首字耗时。由 ChatService 在收到本轮**第一个** GenerationChunk 时调用。
     *
     * @param ttfbMs 从发起请求到收到首个 chunk 的毫秒数（含 SSE 首帧解析，与用户感知一致）
     */
    fun record(modelId: Uuid, ttfbMs: Long) {
        runCatching {
            // 异常值直接丢弃：<50ms 多半是同一次生成的重入，>5min 是无意义超时
            if (ttfbMs <= 0L || ttfbMs > 300_000L) return
            val key = modelId.toString()
            val queue = samples.getOrPut(key) { ArrayDeque<Long>() }
            synchronized(queue) {
                queue.addLast(ttfbMs)
                while (queue.size > MAX_SAMPLES) queue.removeFirst()
            }
            runCatching { Log.d(TAG, "record ${key.take(8)} ttfb=${ttfbMs}ms avg=${average(modelId) ?: -1}ms") }
        }
    }

    /** 该模型的历史平均首字耗时；无样本 → null */
    fun average(modelId: Uuid): Long? = runCatching {
        val queue = samples[modelId.toString()] ?: return null
        val snapshot = synchronized(queue) { queue.toList() }
        if (snapshot.size < MIN_TRUSTED_SAMPLES) return null
        snapshot.sum() / snapshot.size
    }.getOrNull()

    /**
     * 从候选里挑历史最快的一个；**没有任何候选有样本时返回 null**（调用方用池首）。
     *
     * 只给「有样本」的候选排序 —— 没样本的模型不参与比较，避免「一条偶然的
     * 200ms 冷启动样本」把一个从没测过的大模型永久排除。
     */
    fun fastest(ids: List<Uuid>): Uuid? = runCatching {
        if (ids.size < 2) return null
        val scored = ids.mapNotNull { id -> average(id)?.let { id to it } }
        if (scored.isEmpty()) return null
        scored.minByOrNull { it.second }?.first
    }.getOrNull()

    /** 诊断用：当前所有模型的平均延迟（按快慢排序） */
    fun snapshot(): List<Pair<String, Long>> = runCatching {
        samples.mapNotNull { (key, queue) ->
            val list = synchronized(queue) { queue.toList() }
            if (list.isEmpty()) null else key.take(8) to (list.sum() / list.size)
        }.sortedBy { it.second }
    }.getOrDefault(emptyList())

    // ════════════════════════════════════════════════════════════════
    // v5.3.35 ★失败记录：能力不合格的模型必须被降权，而不是"快就选"
    // ════════════════════════════════════════════════════════════════
    //
    // 用户实测的灾难场景：原本用 DeepSeek，中途被路由到智谱 GLM，
    // 该模型"思考一段之后把本该用来调用工具的工具调用直接当正文发给了用户"。
    // 根因（代码级）：`ChatCompletionsAPI:1070` 只在 `abilities.contains(TOOL)` 时
    // 才发 `tools` 参数 —— 池里若有未声明 TOOL 的模型，它根本拿不到工具定义，
    // 却能看到历史里的工具调用，于是只能**模仿成文字吐出来**。
    //
    // 准入过滤在 ModelRouting.kt（能力先于速度）；这里只负责**记住失败**，
    // 让"同一个模型反复坏"不会每轮都再撞一次。

    private val failures = ConcurrentHashMap<String, Int>()

    /** 记一次「该模型在需要工具时没能产出工具调用」的失败（由调用方判定后调用） */
    fun recordFailure(modelId: Uuid) {
        runCatching {
            val key = modelId.toString()
            failures[key] = (failures[key] ?: 0) + 1
            runCatching { Log.w(TAG, "record failure ${key.take(8)} total=${failures[key]}") }
        }
    }

    /** 失败次数（用于路由打分降权） */
    fun failureCount(modelId: Uuid): Int = failures[modelId.toString()] ?: 0

    /** 一次成功后清零，让「修好了」的模型能回到候选（避免永久打入冷宫） */
    fun clearFailure(modelId: Uuid) {
        failures.remove(modelId.toString())
    }

    // ═══ 会话粘性（避免「每轮换一个脑子」）═══
    // 用户 v5.1.7 要求「每次发送都重新选最快的」，但每轮换模型会让对话失去连续性
    // （不同模型的思考风格/工具调用习惯完全不同，用户能直接感知「换了个人」）。
    // 折中：**粘性优先，速度显著更快才切换** —— 见 ModelRouting.pickRoutedModel。

    private val stickyByConversation = ConcurrentHashMap<String, String>()

    fun markSticky(conversationId: String, modelId: Uuid) {
        runCatching { stickyByConversation[conversationId] = modelId.toString() }
    }

    fun stickyOf(conversationId: String): Uuid? = runCatching {
        stickyByConversation[conversationId]?.let { Uuid.parse(it) }
    }.getOrNull()

    fun clearSticky(conversationId: String) {
        stickyByConversation.remove(conversationId)
    }

    /** 测试/诊断用：清空全部样本 */
    fun clear() {
        samples.clear()
        failures.clear()
        stickyByConversation.clear()
    }
}
