/* 【域 B·AI 传输】 */
package me.rerere.ai.util

import java.util.concurrent.ConcurrentHashMap

/**
 * v4.8.118 (B140 v2, 用户定版: 首先保证正常输出): 聚合网关完成门"证据学习层"。
 *
 * 背景: OC/CC 标准完结 = usage/cost 尾包收尾; 但部分通道 (ox 系等) 正常结束时
 * 无任何完成信号, 与"干净边界掐流"在信号层不可区分 (B140 盲区)。
 * v4.8.117 的"并行即判掐流"启发式存在误伤风险 (无信号通道的正常结束在并行
 * 场景会被强制续写) — 本层提供硬证据判据替代:
 *
 *   一旦某 host+model 被观测到能发出 usage 帧 (流内 usage != null) 或以
 *   usage 尾包形态收尾 —— 即"该通道标准完结形状已知" —— 此后其干净边界的
 *   无完成证据关流必然异常 (判掐流, 自动续写)。
 *   从未观测到的通道一律维持 complete (正常输出零风险)。
 *
 * 本进程内学习 (进程重启后由首次正常完成重新习得); 并发安全。
 */
object AggregateCloseEvidence {

    private val usageCapable = ConcurrentHashMap.newKeySet<String>()

    private fun key(host: String, modelId: String): String = host + "|" + modelId

    /** 观测到 usage 证据 (usage 帧或 usage 尾包收尾) — 标记通道标准完结形状已知 */
    fun markUsageCapable(host: String, modelId: String) {
        if (host.isNotBlank() && modelId.isNotBlank()) usageCapable.add(key(host, modelId))
    }

    /** 该通道是否已习得"usage 尾包完结"能力 */
    fun isUsageCapable(host: String, modelId: String): Boolean = key(host, modelId) in usageCapable

    /** 仅测试用 */
    fun resetForTest() {
        usageCapable.clear()
    }
}
