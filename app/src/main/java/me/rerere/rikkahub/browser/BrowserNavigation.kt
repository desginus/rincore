/* 【域 H·语音搜索】 | 地图: docs/APP_MAP.md §H */
package me.rerere.rikkahub.browser


/* ───【自研】BrowserNavigation.kt — v4.8.116 导航生命周期层 (B139 重写)
 * 用户实证 (2026-10-10) 三条 P0 的共同根子:
 *   旧 browser_open/click 在发起导航后立即轮询 document.readyState —— 导航进行中
 *   evaluateJavascript 命中的是【旧文档】的 JS 上下文, 旧页 readyState 早已
 *   "complete" → 立即返回 → current_url/page_title 全是导航前快照 (open example.com
 *   后 location.href 仍是 about:blank; 标题还是上一页的"网页无法打开")。
 *
 * 本层以 WebViewClient 生命周期事件为唯一事实源:
 *   onPageStarted ++generation → onPageFinished finished=generation
 *   shouldInterceptRequest 刷新 lastRequestAtMs (network-idle 依据)
 *   onReceivedError/onReceivedHttpError (仅主帧) 记录导航失败
 * 结算判据 = "新页面已 finish 且 (最后一次网络请求距今 ≥ idleMs)"。
 * 不再依赖旧文档的 readyState 轮询。
 * ───────────────────────────────────────────────────────────────*/
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull

/**
 * 导航状态机 — 由 HeadlessBrowserSession 的 WebViewClient 回调喂入。
 * 所有字段 @Volatile: 回调在主线程, 等待循环在协程 (delay 挂起不阻塞)。
 */
class BrowserNavigationTracker {

    /** 导航代数: onPageStarted 递增 (重定向链每跳递增) */
    @Volatile
    var generation: Long = 0
        private set

    /** 已完成到的代数: onPageFinished 时 = generation */
    @Volatile
    var finishedGeneration: Long = 0
        private set

    /** 最后一次资源请求时间 (network-idle 判据) */
    @Volatile
    var lastRequestAtMs: Long = 0
        private set

    /** 主帧导航错误 (net::ERR_*); 重定向链内新 onPageStarted 会清空 */
    @Volatile
    var mainFrameErrorCode: Int? = null
        private set

    @Volatile
    var mainFrameErrorDesc: String? = null
        private set

    /** 主帧 HTTP >= 400 */
    @Volatile
    var mainFrameHttpCode: Int? = null
        private set

    fun onPageStarted() {
        generation++
        clearError()
    }

    fun onPageFinished() {
        finishedGeneration = generation
    }

    fun onRequest() {
        lastRequestAtMs = System.currentTimeMillis()
    }

    fun onMainFrameError(code: Int, description: String) {
        mainFrameErrorCode = code
        mainFrameErrorDesc = description
    }

    fun onMainFrameHttpError(statusCode: Int) {
        if (mainFrameHttpCode == null) mainFrameHttpCode = statusCode
    }

    fun clearError() {
        mainFrameErrorCode = null
        mainFrameErrorDesc = null
        mainFrameHttpCode = null
    }

    /** 是否有"已开始未完成"的在途导航 (读类工具判断 is_loading) */
    fun hasInFlightNav(): Boolean = finishedGeneration < generation

    /**
     * 结算判定 (纯函数, 单测覆盖):
     * 导航已在 afterGen 之后开始, 且最新一代已 finish, 且网络已静默 idleMs。
     */
    fun isSettled(afterGen: Long, idleMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (generation <= afterGen) return false          // 新导航还没开始
        if (finishedGeneration < generation) return false // 在途未完成
        return nowMs - lastRequestAtMs >= idleMs          // 网络静默窗口
    }
}

/** 一次导航动作的结局 */
data class NavOutcome(
    val navigated: Boolean,      // 动作是否真的触发了导航 (SPA 点击/无操作 = false)
    val settled: Boolean,        // 导航是否在超时内完成结算
    val errorCode: Int?,         // 主帧 net::ERR_* (无 = null)
    val errorDesc: String?,
    val httpCode: Int?,          // 主帧 HTTP >= 400 (无 = null)
) {
    val failed: Boolean get() = errorCode != null || httpCode != null || (navigated && !settled)
}

private const val POLL_INTERVAL_MS = 100L

private suspend fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        if (condition()) return true
        if (System.currentTimeMillis() >= deadline) return false
        kotlinx.coroutines.delay(POLL_INTERVAL_MS)
    }
}

/**
 * 导航 + 结算等待 (P0-1/P0-2 的核心)。[load] 在主线程执行 (loadUrl/goBack/click 后续)。
 *
 * 两阶段: ①宽限窗 [graceMs] 内等导航开始 (onPageStarted) — 没开始 = 动作未触发导航
 * (SPA 路由/纯 JS 交互), 立即返回 navigated=false, 不白等; ②已开始 → 等 finish +
 * network-idle (idleMs), 超时 = settled=false。
 *
 * 失败不抛异常 — 结局由 [NavOutcome] 表达 (mainFrameErrorCode/httpCode/settled),
 * 由调用方决定信封 (success:false + error) 还是抛出 (throw_on_error)。
 */
suspend fun WebView.navigateAndSettle(
    tracker: BrowserNavigationTracker,
    timeoutMs: Long,
    graceMs: Long = 800L,
    idleMs: Long = 500L,
    load: suspend () -> Unit,
): NavOutcome {
    val genBefore = tracker.generation
    tracker.clearError()
    withContext(Dispatchers.Main) { load() }
    val started = awaitCondition(graceMs) { tracker.generation > genBefore }
    if (!started) {
        return NavOutcome(false, true, null, null, null)
    }
    val settled = awaitCondition(timeoutMs) { tracker.isSettled(genBefore, idleMs) }
    return NavOutcome(true, settled, tracker.mainFrameErrorCode, tracker.mainFrameErrorDesc, tracker.mainFrameHttpCode)
}

/**
 * 读类工具的等就绪: 等在途导航结束 + 网络静默 (不主动发起导航)。
 * 返回是否在超时内就绪; 超时也照常返回 (调用方附 page_state 让模型自行判断)。
 */
suspend fun WebView.awaitPageIdle(tracker: BrowserNavigationTracker, timeoutMs: Long, idleMs: Long = 400L): Boolean =
    awaitCondition(timeoutMs) { !tracker.hasInFlightNav() && tracker.isSettled(tracker.generation, idleMs) }

/** 页面快照 — 结算后取真实值 (JS 直读最终文档, 正确处理重定向后的最终 URL) */
data class PageSnapshot(val url: String, val title: String, val readyState: String)

suspend fun WebView.readPageSnapshot(): PageSnapshot {
    val js = "(function(){try{return JSON.stringify({href:location.href,title:document.title,ready:document.readyState});}catch(e){return null;}})()"
    val raw = evaluateJavascriptAsync(js, 2_000L) ?: return PageSnapshot(url.orEmpty(), title.orEmpty(), "unknown")
    // evaluateJavascript 返回值是 JSON 字符串编码 — 双层解码 (与 parseJsResult 同款)
    val fields = runCatching {
        val outer = kotlinx.serialization.json.Json.parseToJsonElement(raw)
        val inner = if (outer is kotlinx.serialization.json.JsonPrimitive && outer.isString) {
            kotlinx.serialization.json.Json.parseToJsonElement(outer.contentOrNull.orEmpty())
        } else outer
        (inner as? kotlinx.serialization.json.JsonObject)
    }.getOrNull() ?: return PageSnapshot(url.orEmpty(), title.orEmpty(), "unknown")
    return PageSnapshot(
        fields["href"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }.orEmpty(),
        fields["title"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }.orEmpty(),
        fields["ready"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }.orEmpty(),
    )
}
