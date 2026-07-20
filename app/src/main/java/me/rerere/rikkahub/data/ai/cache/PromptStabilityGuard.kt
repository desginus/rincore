package me.rerere.rikkahub.data.ai.cache

import android.util.Log
import java.security.MessageDigest

private const val TAG = "PromptStability"

/**
 * 提示词稳定性哨兵 — 确保系统提示词前缀跨请求字节一致。
 *
 * 原理: 前缀缓存依赖请求间的字节匹配。如果系统提示词发生变化(哪怕一个字符),
 * 整个缓存锚点失效, 后续所有对话历史都要重算。
 *
 * 使用:
 *   val guard = PromptStabilityGuard()
 *   guard.check(systemPromptText)  // 每次构建系统提示词后调用
 *   // 如果 hash 变化, 日志会输出警告和 diff
 */
class PromptStabilityGuard {
    private var lastHash: String? = null
    private var lastLength: Int = -1
    private var checkCount: Int = 0

    /** 校验提示词稳定性。首次调用记录基线, 后续调用对比。 */
    fun check(prompt: String) {
        checkCount++
        val hash = sha256(prompt)
        val len = prompt.length

        if (lastHash == null) {
            lastHash = hash
            lastLength = len
            Log.i(TAG, "基线: ${len}c, hash=${hash.take(8)}")
            return
        }

        if (hash != lastHash) {
            val diff = len - lastLength
            Log.w(TAG, buildString {
                append("⚠ 系统提示词已变更 (检查#$checkCount)! 缓存锚点失效!")
                append(" 旧:${lastLength}c → 新:${len}c (${if(diff>0)"+$diff" else "$diff"}c)")
                append(" 旧hash:${lastHash!!.take(8)} 新hash:${hash.take(8)}")
            })
        } else if (len != lastLength) {
            Log.w(TAG, "系统提示词 hash 一致但长度变化: ${lastLength}c → ${len}c")
        }

        lastHash = hash
        lastLength = len
    }

    val isStable: Boolean
        get() = checkCount <= 1 || lastHash != null

    fun reset() {
        lastHash = null
        lastLength = -1
        checkCount = 0
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
