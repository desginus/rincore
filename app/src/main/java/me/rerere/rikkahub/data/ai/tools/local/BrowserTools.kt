/* 【域 C·工具系统】 | 地图: docs/APP_MAP.md §C */
package me.rerere.rikkahub.data.ai.tools.local


/* ───【自研】BrowserTools.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * v4.8.116 (B139 用户实证六项, 整文件重写):
 *   P0-1 乐观返回   → 导航类工具全部经 navigateAndSettle (load+network-idle),
 *                     current_url/page_title 取结算后 JS 快照 (重定向最终 URL)
 *   P0-2 静默吞错   → 主帧 net::ERR_xxx / HTTP>=4xx / 超时捕获, 失败返回
 *                     success:false+error+final_url; throw_on_error 可抛出
 *   P0-3 受控输入   → type/select 走原型原生 value setter + input/change 事件
 *                     (React/Vue value tracker 不再被绕过)
 *   P1-4 语义混淆   → 读类结果附 page_state(ready_state/is_loading/url/title);
 *                     未加载完时 selector_not_found 升级为 page_not_ready;
 *                     新增 browser_wait_for_load
 *   P1-5 同批竞态   → BrowserControllerHandle 全局操作互斥, 读类天然排在导航后
 *   P2-6 截图不可见 → 截图统一落绑定工作区 /workspace/browser-shots/ (沙箱直读)
 *                     + render_urls/render_markdown (v4.8.115 统一地址链) + TTL 清理
 * ───────────────────────────────────────────────────────────────*/
import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.browser.BrowserCacheSweeper
import me.rerere.rikkahub.browser.BrowserController
import me.rerere.rikkahub.browser.BrowserControllerHandle
import me.rerere.rikkahub.browser.BrowserDiffHelper
import me.rerere.rikkahub.browser.BrowserNavigationTracker
import me.rerere.rikkahub.browser.BrowserToolDefaults
import me.rerere.rikkahub.browser.HeadlessBrowserSessionPool
import me.rerere.rikkahub.browser.NavOutcome
import me.rerere.rikkahub.browser.PageSnapshot
import me.rerere.rikkahub.browser.ReadabilityRunner.runReadability
import me.rerere.rikkahub.browser.awaitPageIdle
import me.rerere.rikkahub.browser.evaluateJavascriptAsync
import me.rerere.rikkahub.browser.navigateAndSettle
import me.rerere.rikkahub.browser.readPageSnapshot
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.ai.tools.buildRenderMarkdown
import me.rerere.rikkahub.data.ai.tools.buildRenderUrl
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import java.io.File
import java.io.FileOutputStream

private const val MAX_SCREENSHOT_HEIGHT_PX = 8192
private const val SCREENSHOT_CACHE_SUBDIR = "browser-shots"
private const val EVAL_JS_MAX_RESULT_CHARS = 64 * 1024
private val toolTimeoutMs: Long get() = BrowserController.perToolTimeoutMs

private fun timeoutEnvelope(toolName: String): JsonObject = buildJsonObject {
    put("error", "tool_timeout")
    put("detail", "$toolName exceeded ${toolTimeoutMs}ms budget")
}

private fun missingArgEnvelope(name: String, detail: String): JsonObject = buildJsonObject {
    put("error", "missing_arg")
    put("arg", name)
    put("detail", detail)
}

private fun navFailureEnvelope(outcome: NavOutcome, snap: PageSnapshot): JsonObject = buildJsonObject {
    put("success", false)
    val err = when {
        outcome.errorCode != null -> outcome.errorDesc?.takeIf { it.isNotBlank() } ?: "net::ERR_FAILED"
        outcome.httpCode != null -> "HTTP_${outcome.httpCode}"
        !outcome.settled -> "NET_TIMEOUT"
        else -> "NAV_NO_START"
    }
    put("error", err)
    put("detail", "Navigation failed or did not settle; the target page was NOT loaded. Do not treat the previous page as the target page.")
    put("final_url", snap.url)
    put("page_title", snap.title)
    put("recovery", "Verify the URL/domain and retry, or open a different URL. On timeout, the site may be unreachable from this network.")
}

private fun pageStateJson(snap: PageSnapshot, tracker: BrowserNavigationTracker?): JsonObject = buildJsonObject {
    put("ready_state", snap.readyState)
    put("is_loading", tracker?.hasInFlightNav() == true || snap.readyState != "complete")
    put("url", snap.url)
    put("title", snap.title)
}

/**
 * v4.8.116 (P1-4): 读类结果统一后处理 — ①selector_not_found 且页面未加载完时
 * 升级为 page_not_ready（"还没加载完"与"真没有"不再混淆）；②所有读结果附
 * page_state，模型可自判页面状态。
 */
private fun finalizeReadResult(
    res: JsonObject,
    snap: PageSnapshot,
    tracker: BrowserNavigationTracker?,
): JsonObject {
    val err = res["error"]?.jsonPrimitive?.contentOrNull
    if (err == "selector_not_found" && snap.readyState != "complete") {
        return buildJsonObject {
            put("error", "page_not_ready")
            put("detail", "Page was still loading when the read ran (readyState=${snap.readyState}) — element absence is not conclusive.")
            put("page_state", pageStateJson(snap, tracker))
            put("recovery", "Call browser_wait_for_load, then repeat the same read.")
        }
    }
    return buildJsonObject {
        res.forEach { (k, v) -> put(k, v) }
        put("page_state", pageStateJson(snap, tracker))
    }
}

private fun textPart(obj: JsonObject): List<UIMessagePart> =
    listOf(UIMessagePart.Text(Json.encodeToString(obj)))

private fun jsString(s: String): String = JsonPrimitive(s).toString()

// ---- Read tools --------------------------------------------------------------------------

fun browserOpenTool(context: Context, callerConvId: () -> String): Tool = Tool(
    name = BrowserToolDefaults.OPEN,
    description = "Open a URL in the headless browser. Waits for the navigation to settle (page load + network idle, redirects resolved) and returns the FINAL url/page_title — never a stale snapshot. Navigation failures (DNS error / timeout / HTTP error) return success:false with an error code; throw_on_error=true raises instead.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("url", buildJsonObject {
                put("type", "string")
                put("description", "Fully-qualified URL to navigate to (must start with http:// or https://)")
            })
            put("throw_on_error", buildJsonObject {
                put("type", "boolean")
                put("description", "If true, throw on navigation failure instead of returning success:false (default false)")
            })
        }, required = listOf("url"))
    },
    execute = { input ->
        val rawUrl = input.jsonObject["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        // 安全边界: 仅允许 http/https, 拒绝 file:// content:// javascript: 等
        val scheme = rawUrl?.let { android.net.Uri.parse(it.trim()).scheme?.lowercase() }
        if (rawUrl == null || scheme !in setOf("http", "https")) {
            return@Tool textPart(missingArgEnvelope("url",
                if (rawUrl == null) "url is required" else "url scheme must be http or https, got: $scheme"))
        }
        val throwOnError = input.jsonObject["throw_on_error"]?.jsonPrimitive?.booleanOrNull == true
        val convId = callerConvId()
        val session = HeadlessBrowserSessionPool.getOrCreate(context, convId)
        val webView = session.start(convId)
        if (!BrowserController.bindHeadless(convId, webView, session.navigationTracker)) {
            return@Tool textPart(BrowserController.bindBusyEnvelope())
        }
        BrowserController.startTaskWindow()
        BrowserCacheSweeper.sweep(context)
        val out = withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                val tracker = controller.activeTracker()
                // B139 P0-1/P0-2: load 事件 + network-idle 结算 — 旧实现轮询旧文档的
                // readyState 立即返回, url/title 全是导航前快照
                val outcome = webView.navigateAndSettle(
                    tracker,
                    timeoutMs = 15_000L,
                    graceMs = 1_500L,
                ) { webView.loadUrl(rawUrl) }
                val snap = webView.readPageSnapshot()
                if (outcome.failed) {
                    val env = navFailureEnvelope(outcome, snap)
                    if (throwOnError) throw IllegalStateException("browser_open failed: ${env["error"]} (${snap.url})")
                    env
                } else {
                    BrowserController.appendAction("Open: $rawUrl")
                    buildJsonObject {
                        put("success", true)
                        put("current_url", snap.url)
                        put("page_title", snap.title)
                        put("ready_state", snap.readyState)
                    }
                }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.OPEN)
        textPart(out)
    },
)

fun browserCurrentUrlTool(): Tool = Tool(
    name = BrowserToolDefaults.CURRENT_URL,
    description = "Return the browser's current URL, page title and load state.",
    execute = {
        val out = BrowserControllerHandle.withController {
            val snap = webView.readPageSnapshot()
            buildJsonObject {
                put("current_url", snap.url)
                put("page_title", snap.title)
                put("ready_state", snap.readyState)
            }
        }
        textPart(out)
    },
)

fun browserScreenshotTool(context: Context, invocationContext: ToolInvocationContext): Tool = Tool(
    name = BrowserToolDefaults.SCREENSHOT,
    description = "Capture a full-viewport screenshot as PNG. Saved into the bound workspace at /workspace/browser-shots/ (sandbox-readable) with render_urls/render_markdown for inline display; falls back to app cache when no workspace is bound (path then is app-private). Screenshots are TTL-cleaned (24h, keep latest 50).",
    execute = {
        val out = withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                val w = webView.width.coerceAtLeast(1)
                val h = webView.height.coerceAtLeast(1).coerceAtMost(MAX_SCREENSHOT_HEIGHT_PX)
                val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                val pngBytes: ByteArray? = try {
                    val canvas = android.graphics.Canvas(bitmap)
                    webView.draw(canvas)
                    java.io.ByteArrayOutputStream().use { bos ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
                        bos.toByteArray()
                    }
                } finally { bitmap.recycle() }
                if (pngBytes == null) {
                    return@withController buildJsonObject { put("success", false); put("error", "capture_failed") }
                }
                BrowserController.appendAction("Screenshot")
                val ts = System.currentTimeMillis()
                val wsId = invocationContext.workspaceId
                val repo = runCatching {
                    org.koin.java.KoinJavaComponent.getKoin().get<WorkspaceRepository>()
                }.getOrNull()
                val rootfsPath = "/workspace/browser-shots/shot-$ts.png"
                val wroteToWorkspace = wsId != null &&
                    repo?.writeBinaryInRootfs(wsId, rootfsPath, pngBytes, invocationContext.workspaceCwd) == true
                if (wroteToWorkspace && wsId != null) {
                    repo?.sweepBrowserShotsInRootfs(wsId, invocationContext.workspaceCwd)
                    val renderUrl = buildRenderUrl(wsId, rootfsPath, invocationContext.workspaceCwd)
                    buildJsonObject {
                        put("success", true)
                        put("path", rootfsPath)
                        put("sandbox_path", rootfsPath)
                        put("storage", "workspace")
                        put("width", w)
                        put("height", h)
                        put("render_urls", buildJsonArray { add(renderUrl) })
                        buildRenderMarkdown(wsId, listOf(rootfsPath), invocationContext.workspaceCwd)
                            ?.let { put("render_markdown", it) }
                    }
                } else {
                    val cacheDir = File(context.cacheDir, SCREENSHOT_CACHE_SUBDIR).apply { mkdirs() }
                    val outFile = File(cacheDir, "shot-$ts.png")
                    FileOutputStream(outFile).use { os -> os.write(pngBytes) }
                    BrowserCacheSweeper.sweep(context)
                    buildJsonObject {
                        put("success", true)
                        put("path", outFile.absolutePath)
                        put("storage", "cache")
                        put("width", w)
                        put("height", h)
                        put("note", "No workspace bound — file is app-private (not sandbox-readable). Bind a workspace for inline display.")
                    }
                }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.SCREENSHOT)
        textPart(out)
    },
)

fun browserGetTextTool(): Tool = Tool(
    name = BrowserToolDefaults.GET_TEXT,
    description = "Extract readable text from the current page. extract_mode: 'auto' (default, tries Readability then falls back to body.innerText), 'readability' (forces article extraction), 'raw' (selector-based innerText). Optional selector overrides Readability. Waits for in-flight navigation to settle first (wait_ready, default true); returns page_state.",
    parameters = { getTextSchema(8000) },
    execute = { input -> textPart(runGetText(input)) },
)

fun browserGetDomTool(): Tool = Tool(
    name = BrowserToolDefaults.GET_DOM,
    description = "Extract the outerHTML of an element matching a CSS selector. Defaults to 'body'. Clamped to max_chars (default 4000). Waits for in-flight navigation to settle first (wait_ready, default true); a missing element on a still-loading page reports page_not_ready instead of selector_not_found; returns page_state.",
    parameters = { selectorAndMaxCharsSchema(4000, required = false) },
    execute = { input ->
        textPart(runReadHelper(input, BrowserToolDefaults.GET_DOM, 4000) { sel, max ->
            """(function(){
                try {
                    var el = document.querySelector(${jsString(sel)});
                    if (!el) return JSON.stringify({error:'selector_not_found'});
                    var clone = el.cloneNode(true);
                    clone.querySelectorAll('script,style,noscript').forEach(function(n){n.remove();});
                    var html = clone.outerHTML;
                    var trunc = false;
                    if (html.length > $max) { html = html.substring(0, $max); trunc = true; }
                    return JSON.stringify({html:html, truncated:trunc});
                } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
            })()"""
        })
    },
)

fun browserGetLinksTool(): Tool = Tool(
    name = BrowserToolDefaults.GET_LINKS,
    description = "List all <a href> links on the page with their text content. Caps at 200 links. Waits for in-flight navigation to settle first (wait_ready, default true); returns page_state.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("wait_ready", buildJsonObject { put("type", "boolean"); put("description", "Wait for in-flight navigation to settle before reading (default true)") })
        })
    },
    execute = {
        val out = withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                val tracker = controller.activeTracker()
                webView.awaitPageIdle(tracker, 5_000L)
                val snap = webView.readPageSnapshot()
                val js = """(function(){
                    try {
                        var links = document.querySelectorAll('a[href]');
                        var result = [];
                        for (var i = 0; i < Math.min(links.length, 200); i++) {
                            var a = links[i];
                            result.push({
                                href: a.href,
                                text: (a.innerText || a.textContent || '').replace(/\s+/g,' ').trim().substring(0, 200)
                            });
                        }
                        return JSON.stringify({links: result, total: links.length});
                    } catch(e) { return JSON.stringify({error:'js_failed'}); }
                })()"""
                val res = parseJsResult(webView.evaluateJavascriptAsync(js))
                finalizeReadResult(res, snap, tracker)
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.GET_LINKS)
        textPart(out)
    },
)

fun browserBackTool(): Tool = Tool(
    name = BrowserToolDefaults.BACK,
    description = "Navigate back in browser history. Waits for the navigation to settle and returns the FINAL current_url/page_title; failures (no history / load error) return success:false with an error code. throw_on_error=true raises instead.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("throw_on_error", buildJsonObject { put("type", "boolean"); put("description", "If true, throw on navigation failure (default false)") })
        })
    },
    execute = { input -> textPart(runHistoryNav(BrowserToolDefaults.BACK, forward = false, input = input)) },
)

fun browserForwardTool(): Tool = Tool(
    name = BrowserToolDefaults.FORWARD,
    description = "Navigate forward in browser history. Waits for the navigation to settle and returns the FINAL current_url/page_title; failures return success:false with an error code. throw_on_error=true raises instead.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("throw_on_error", buildJsonObject { put("type", "boolean"); put("description", "If true, throw on navigation failure (default false)") })
        })
    },
    execute = { input -> textPart(runHistoryNav(BrowserToolDefaults.FORWARD, forward = true, input = input)) },
)

internal fun buildWaitForPredicate(selector: String, state: String, containsText: String?): String {
    val sel = jsString(selector)
    val txt = if (containsText != null) {
        val escaped = jsString(containsText)
        "function(el){var t=(el.innerText||el.textContent||'');return t.indexOf($escaped)!==-1;}"
    } else "function(){return true;}"
    val visibleCheck = "function(el){" +
        "var r=el.getBoundingClientRect();" +
        "var s=getComputedStyle(el);" +
        "return r.width>0 && r.height>0 && s.visibility!=='hidden' && s.display!=='none';" +
        "}"
    return when (state) {
        "detached" -> "(function(){try{return document.querySelector($sel)===null;}catch(e){return false;}})()"
        "hidden" -> "(function(){try{" +
            "var el=document.querySelector($sel);return !el || !$visibleCheck(el);" +
            "}catch(e){return false;}})()"
        "visible" -> "(function(){try{" +
            "var el=document.querySelector($sel);return el && $visibleCheck(el) && $txt(el);" +
            "}catch(e){return false;}})()"
        else -> "(function(){try{" + // "attached" (default)
            "var el=document.querySelector($sel);return !!el && $txt(el);" +
            "}catch(e){return false;}})()"
    }
}

private val WAIT_FOR_STATES = setOf("attached", "detached", "visible", "hidden")

fun browserWaitForTool(): Tool = Tool(
    name = BrowserToolDefaults.WAIT_FOR,
    description = "Wait for a CSS selector to reach a target state. state: attached (default), detached, visible, hidden. Optional contains_text filters elements whose text contains the given substring. timeout_ms caps at the per-tool budget.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject { put("type", "string"); put("description", "CSS selector to watch") })
            put("state", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { add("attached"); add("detached"); add("visible"); add("hidden") })
                put("description", "Target state (default 'attached')")
            })
            put("contains_text", buildJsonObject { put("type", "string"); put("description", "Optional substring to match element text") })
            put("timeout_ms", buildJsonObject { put("type", "integer"); put("description", "Max wait ms (default 10000)") })
        }, required = listOf("selector"))
    },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val rawState = input.jsonObject["state"]?.jsonPrimitive?.contentOrNull?.lowercase()
        val out = when {
            selector == null -> missingArgEnvelope("selector", "selector is required")
            rawState != null && rawState !in WAIT_FOR_STATES -> missingArgEnvelope("state", "state must be [attached, detached, visible, hidden]")
            else -> {
                val state = rawState ?: "attached"
                val containsText = input.jsonObject["contains_text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
                val timeoutMs = (input.jsonObject["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 10_000).toLong().coerceIn(200L, toolTimeoutMs)
                withTimeoutOrNull(toolTimeoutMs) {
                    BrowserControllerHandle.withController {
                        val started = System.currentTimeMillis()
                        val deadline = started + timeoutMs
                        val js = buildWaitForPredicate(selector, state, containsText)
                        var found = false
                        while (System.currentTimeMillis() < deadline) {
                            val raw = webView.evaluateJavascriptAsync(js, 1_500L)
                            if (raw == "true") { found = true; break }
                            delay(200)
                        }
                        buildJsonObject {
                            put("found", found)
                            put("elapsed_ms", System.currentTimeMillis() - started)
                            put("state", state)
                            if (containsText != null) put("contains_text", containsText)
                        }
                    }
                } ?: timeoutEnvelope(BrowserToolDefaults.WAIT_FOR)
            }
        }
        textPart(out)
    },
)

/**
 * v4.8.116 (P1-4): 显式等就绪 — 在途导航结束 + 网络静默 + readyState=complete。
 * 消除"页面还没渲染完就读"与"元素真不存在"的语义混淆。
 */
fun browserWaitForLoadTool(): Tool = Tool(
    name = BrowserToolDefaults.WAIT_FOR_LOAD,
    description = "Wait until the page finishes loading: any in-flight navigation completes, the network goes quiet for idle_ms, and document.readyState becomes 'complete'. Use after browser_open/click/submit before reading. Returns {settled, page_state}.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("timeout_ms", buildJsonObject { put("type", "integer"); put("description", "Max wait ms (default 10000)") })
            put("idle_ms", buildJsonObject { put("type", "integer"); put("description", "Network-quiet window in ms (default 400)") })
        })
    },
    execute = { input ->
        val timeoutMs = (input.jsonObject["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 10_000).toLong().coerceIn(200L, toolTimeoutMs)
        val idleMs = (input.jsonObject["idle_ms"]?.jsonPrimitive?.intOrNull ?: 400).toLong().coerceIn(0L, 5_000L)
        val out = withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                val tracker = controller.activeTracker()
                val settled = webView.awaitPageIdle(tracker, timeoutMs, idleMs)
                val snap = webView.readPageSnapshot()
                buildJsonObject {
                    put("settled", settled)
                    put("page_state", pageStateJson(snap, tracker))
                }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.WAIT_FOR_LOAD)
        textPart(out)
    },
)

// ---- Write tools --------------------------------------------------------------------------

fun browserClickTool(): Tool = Tool(
    name = BrowserToolDefaults.CLICK,
    description = "Click an element matching a CSS selector. If the click triggers a navigation, waits for it to settle (load + network idle) and returns the FINAL post_click_url/page_title; SPA clicks return immediately. Navigation failures return success:false with an error code. Returns diff by default; pass full:true to skip diff.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject { put("type", "string"); put("description", "CSS selector to click") })
            put("full", buildJsonObject { put("type", "boolean"); put("description", "Skip diff (default false)") })
            put("throw_on_error", buildJsonObject { put("type", "boolean"); put("description", "If true, throw on navigation failure instead of returning success:false (default false)") })
        }, required = listOf("selector"))
    },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val full = parseFullArg(input)
        val throwOnError = input.jsonObject["throw_on_error"]?.jsonPrimitive?.booleanOrNull == true
        val out = if (selector == null) missingArgEnvelope("selector", "selector is required")
        else withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                val tracker = controller.activeTracker()
                withDiff(full) {
                    var actionError: JsonObject? = null
                    // B139: 点击本身放进结算等待 — 点击触发的导航等到 finish + idle 后
                    // 才读 post_click_url (不再返回旧 URL); SPA 点击宽限窗后立即返回
                    val outcome = webView.navigateAndSettle(tracker, timeoutMs = 12_000L) {
                        val res = parseJsResult(webView.evaluateJavascriptAsync(clickElementJs(selector)))
                        if (res.containsKey("error")) actionError = res
                    }
                    if (actionError != null) return@withDiff actionError!!
                    val snap = webView.readPageSnapshot()
                    if (outcome.failed) {
                        val env = navFailureEnvelope(outcome, snap)
                        if (throwOnError) throw IllegalStateException("browser_click failed: ${env["error"]} (${snap.url})")
                        return@withDiff env
                    }
                    BrowserController.appendAction("Click: $selector")
                    buildJsonObject {
                        put("success", true)
                        put("post_click_url", snap.url)
                        put("page_title", snap.title)
                    }
                }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.CLICK)
        textPart(out)
    },
)

fun browserTypeTool(): Tool = Tool(
    name = BrowserToolDefaults.TYPE,
    description = "Type text into an input/textarea/contenteditable element. Uses the NATIVE prototype value setter + input/change events so React/Vue controlled inputs receive the value (direct el.value assignment is bypassed by their state). Focuses, optionally clears first. Returns diff by default; pass full:true to skip.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject { put("type", "string"); put("description", "CSS selector of the input") })
            put("text", buildJsonObject { put("type", "string"); put("description", "Text to type") })
            put("clear", buildJsonObject { put("type", "boolean"); put("description", "Clear the field first (default true)") })
            put("full", buildJsonObject { put("type", "boolean"); put("description", "Skip diff (default false)") })
        }, required = listOf("selector", "text"))
    },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val text = input.jsonObject["text"]?.jsonPrimitive?.contentOrNull
        val clear = input.jsonObject["clear"]?.jsonPrimitive?.booleanOrNull ?: true
        val full = parseFullArg(input)
        val out = when {
            selector == null -> missingArgEnvelope("selector", "selector is required")
            text == null -> missingArgEnvelope("text", "text is required (use empty string to clear)")
            else -> withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    withDiff(full) {
                        val clearFlag = if (clear) "true" else "false"
                        val js = """(function(){
                            try {
                                var el = document.querySelector(${jsString(selector)});
                                if (!el) return JSON.stringify({error:'selector_not_found'});
                                el.focus();
                                var text = ${jsString(text)};
                                if (el.isContentEditable && el.tagName !== 'INPUT' && el.tagName !== 'TEXTAREA') {
                                    if ($clearFlag) el.textContent = '';
                                    el.textContent = (el.textContent || '') + text;
                                    el.dispatchEvent(new Event('input', {bubbles:true}));
                                    el.dispatchEvent(new Event('change', {bubbles:true}));
                                    return JSON.stringify({typed:true, via:'contenteditable'});
                                }
                                if (el.tagName !== 'INPUT' && el.tagName !== 'TEXTAREA') {
                                    return JSON.stringify({error:'not_inputable', tag: el.tagName});
                                }
                                var proto = el.tagName === 'TEXTAREA'
                                    ? window.HTMLTextAreaElement.prototype
                                    : window.HTMLInputElement.prototype;
                                var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                                var finalVal = ($clearFlag ? '' : (el.value || '')) + text;
                                if (desc && desc.set) { desc.set.call(el, finalVal); }
                                else { el.value = finalVal; }
                                el.dispatchEvent(new Event('input', {bubbles:true}));
                                el.dispatchEvent(new Event('change', {bubbles:true}));
                                return JSON.stringify({typed:true, via: (desc && desc.set) ? 'native_setter' : 'direct_value'});
                            } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                        })()"""
                        val res = parseJsResult(webView.evaluateJavascriptAsync(js))
                        if (res.containsKey("error")) return@withDiff res
                        BrowserController.appendAction("Typed into $selector")
                        buildJsonObject { put("success", true) }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.TYPE)
        }
        textPart(out)
    },
)

fun browserScrollTool(): Tool = Tool(
    name = BrowserToolDefaults.SCROLL,
    description = "Scroll the page. direction: up/down/top/bottom. amount in pixels (default 600, ignored for top/bottom).",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("direction", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { add("up"); add("down"); add("top"); add("bottom") })
            })
            put("amount", buildJsonObject { put("type", "integer"); put("description", "Scroll distance in px (default 600)") })
        }, required = listOf("direction"))
    },
    execute = { input ->
        val direction = input.jsonObject["direction"]?.jsonPrimitive?.contentOrNull
        val amount = input.jsonObject["amount"]?.jsonPrimitive?.intOrNull ?: 600
        val out = if (direction == null || direction !in setOf("up", "down", "top", "bottom")) {
            missingArgEnvelope("direction", "direction must be [up, down, top, bottom]")
        } else withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                val js = """(function(){
                    try {
                        switch (${jsString(direction)}) {
                            case 'up': window.scrollBy(0, -$amount); break;
                            case 'down': window.scrollBy(0, $amount); break;
                            case 'top': window.scrollTo(0, 0); break;
                            case 'bottom': window.scrollTo(0, document.body.scrollHeight); break;
                        }
                        return JSON.stringify({scroll_y: Math.round(window.scrollY)});
                    } catch(e) { return JSON.stringify({error:'js_failed'}); }
                })()"""
                val res = parseJsResult(webView.evaluateJavascriptAsync(js))
                if (res.containsKey("error")) return@withController res
                BrowserController.appendAction("Scroll $direction")
                buildJsonObject { put("success", true); put("scroll_y", res["scroll_y"]?.jsonPrimitive?.intOrNull ?: 0) }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.SCROLL)
        textPart(out)
    },
)

fun browserSubmitTool(): Tool = Tool(
    name = BrowserToolDefaults.SUBMIT,
    description = "Submit a form. If selector is a <button type=submit>, click it; otherwise locates the enclosing <form> and calls .submit(). Waits for the triggered navigation to settle and returns the FINAL post_submit_url; failures return success:false with an error code. Returns diff by default; pass full:true to skip.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject { put("type", "string"); put("description", "CSS selector of a submit button or element inside the target form") })
            put("full", buildJsonObject { put("type", "boolean"); put("description", "Skip diff (default false)") })
            put("throw_on_error", buildJsonObject { put("type", "boolean"); put("description", "If true, throw on navigation failure instead of returning success:false (default false)") })
        }, required = listOf("selector"))
    },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val full = parseFullArg(input)
        val throwOnError = input.jsonObject["throw_on_error"]?.jsonPrimitive?.booleanOrNull == true
        val out = if (selector == null) missingArgEnvelope("selector", "selector is required")
        else withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                val tracker = controller.activeTracker()
                withDiff(full) {
                    var actionError: JsonObject? = null
                    val outcome = webView.navigateAndSettle(tracker, timeoutMs = 12_000L) {
                        val js = """(function(){
                            try {
                                var el = document.querySelector(${jsString(selector)});
                                if (!el) return JSON.stringify({error:'selector_not_found'});
                                if (el.tagName === 'BUTTON' && (el.type === 'submit' || el.type === '')) {
                                    el.click();
                                    return JSON.stringify({submitted:true, via:'button_click'});
                                }
                                var form = el.closest('form');
                                if (!form) return JSON.stringify({error:'no_enclosing_form'});
                                if (typeof form.requestSubmit === 'function') form.requestSubmit();
                                else form.submit();
                                return JSON.stringify({submitted:true, via:'form_submit'});
                            } catch(e) { return JSON.stringify({error:'js_failed'}); }
                        })()"""
                        val res = parseJsResult(webView.evaluateJavascriptAsync(js))
                        if (res.containsKey("error")) actionError = res
                    }
                    if (actionError != null) return@withDiff actionError!!
                    val snap = webView.readPageSnapshot()
                    if (outcome.failed) {
                        val env = navFailureEnvelope(outcome, snap)
                        if (throwOnError) throw IllegalStateException("browser_submit failed: ${env["error"]} (${snap.url})")
                        return@withDiff env
                    }
                    BrowserController.appendAction("Submit: $selector")
                    buildJsonObject {
                        put("success", true)
                        put("post_submit_url", snap.url)
                        put("page_title", snap.title)
                    }
                }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.SUBMIT)
        textPart(out)
    },
)

fun browserSelectTool(): Tool = Tool(
    name = BrowserToolDefaults.SELECT,
    description = "Set a <select> element's value via the NATIVE prototype value setter + change/input events (React/Vue compatible). Returns diff by default; pass full:true to skip.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject { put("type", "string"); put("description", "CSS selector of the <select>") })
            put("value", buildJsonObject { put("type", "string"); put("description", "The option value to set") })
            put("full", buildJsonObject { put("type", "boolean"); put("description", "Skip diff (default false)") })
        }, required = listOf("selector", "value"))
    },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val value = input.jsonObject["value"]?.jsonPrimitive?.contentOrNull
        val full = parseFullArg(input)
        val out = when {
            selector == null -> missingArgEnvelope("selector", "selector is required")
            value == null -> missingArgEnvelope("value", "value is required")
            else -> withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    withDiff(full) {
                        val js = """(function(){
                            try {
                                var el = document.querySelector(${jsString(selector)});
                                if (!el) return JSON.stringify({error:'selector_not_found'});
                                if (el.tagName !== 'SELECT') return JSON.stringify({error:'not_a_select'});
                                var desc = Object.getOwnPropertyDescriptor(window.HTMLSelectElement.prototype, 'value');
                                if (desc && desc.set) { desc.set.call(el, ${jsString(value)}); }
                                else { el.value = ${jsString(value)}; }
                                el.dispatchEvent(new Event('change', {bubbles:true}));
                                el.dispatchEvent(new Event('input', {bubbles:true}));
                                return JSON.stringify({selected:true});
                            } catch(e) { return JSON.stringify({error:'js_failed'}); }
                        })()"""
                        val res = parseJsResult(webView.evaluateJavascriptAsync(js))
                        if (res.containsKey("error")) return@withDiff res
                        BrowserController.appendAction("Select: $selector=$value")
                        buildJsonObject { put("success", true) }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.SELECT)
        }
        textPart(out)
    },
)

fun browserPressKeyTool(): Tool = Tool(
    name = BrowserToolDefaults.PRESS_KEY,
    description = "Synthesize keydown + keyup events on the active element. Use KeyboardEvent.key values (Enter, Escape, ArrowDown, Tab). Returns diff by default; pass full:true to skip.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("key", buildJsonObject { put("type", "string"); put("description", "KeyboardEvent.key value (e.g. 'Enter', 'Escape')") })
            put("full", buildJsonObject { put("type", "boolean"); put("description", "Skip diff (default false)") })
        }, required = listOf("key"))
    },
    execute = { input ->
        val key = input.jsonObject["key"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.take(32)
        val full = parseFullArg(input)
        val out = if (key == null) missingArgEnvelope("key", "key is required (e.g. 'Enter', 'Escape')")
        else withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                withDiff(full) {
                    val js = """(function(){
                        try {
                            var el = document.activeElement || document.body;
                            var down = new KeyboardEvent('keydown', {key:${jsString(key)}, bubbles:true, cancelable:true});
                            var up = new KeyboardEvent('keyup', {key:${jsString(key)}, bubbles:true, cancelable:true});
                            el.dispatchEvent(down);
                            el.dispatchEvent(up);
                            return JSON.stringify({pressed:true});
                        } catch(e) { return JSON.stringify({error:'js_failed'}); }
                    })()"""
                    val res = parseJsResult(webView.evaluateJavascriptAsync(js))
                    if (res.containsKey("error")) return@withDiff res
                    BrowserController.appendAction("Press key: $key")
                    buildJsonObject { put("success", true) }
                }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.PRESS_KEY)
        textPart(out)
    },
)

fun browserEvalJsTool(): Tool = Tool(
    name = BrowserToolDefaults.EVAL_JS,
    description = "Run arbitrary JavaScript in the page and return its last expression. The returned value is JSON-encoded. Always requires approval.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("code", buildJsonObject { put("type", "string"); put("description", "JavaScript to evaluate") })
        }, required = listOf("code"))
    },
    execute = { input ->
        val code = input.jsonObject["code"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val out = if (code == null) missingArgEnvelope("code", "code is required")
        else withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                val raw = webView.evaluateJavascriptAsync(code, toolTimeoutMs - 1_000L)
                BrowserController.appendAction("Run JS")
                val (clipped, truncated) = clipText(raw ?: "null", EVAL_JS_MAX_RESULT_CHARS)
                buildJsonObject { put("result", clipped); if (truncated) put("truncated", true) }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.EVAL_JS)
        textPart(out)
    },
)

fun browserClickAndReadTool(): Tool = Tool(
    name = BrowserToolDefaults.CLICK_AND_READ,
    description = "One-shot click + read. Clicks, awaits the triggered navigation to settle (or returns immediately for SPA clicks), then returns diff (default) or extracted text with the FINAL post_click_url/page_title. extract_mode: diff (default), auto, readability, raw. max_chars caps text (default 4000).",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject { put("type", "string"); put("description", "CSS selector to click") })
            put("extract_mode", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { add("diff"); add("auto"); add("readability"); add("raw") })
                put("description", "diff (default) or text extraction mode")
            })
            put("max_chars", buildJsonObject { put("type", "integer"); put("description", "Caps text length (default 4000)") })
        }, required = listOf("selector"))
    },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val mode = input.jsonObject["extract_mode"]?.jsonPrimitive?.contentOrNull?.lowercase()
            ?.takeIf { it in setOf("diff", "auto", "readability", "raw") } ?: "diff"
        val maxChars = (input.jsonObject["max_chars"]?.jsonPrimitive?.intOrNull ?: 4000).coerceIn(100, 64 * 1024)
        val out = if (selector == null) missingArgEnvelope("selector", "selector is required")
        else withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                val tracker = controller.activeTracker()
                val before = if (mode == "diff") captureBodyText() else ""
                var clickError: JsonObject? = null
                val outcome = webView.navigateAndSettle(tracker, timeoutMs = 12_000L) {
                    val res = parseJsResult(webView.evaluateJavascriptAsync(clickElementJs(selector)))
                    if (res.containsKey("error")) clickError = res
                }
                if (clickError != null) return@withController clickError!!
                if (outcome.failed) return@withController navFailureEnvelope(outcome, webView.readPageSnapshot())
                BrowserController.appendAction("Click+read: $selector")
                val snap = webView.readPageSnapshot()
                val postUrl = snap.url
                val postTitle = snap.title
                when (mode) {
                    "diff" -> {
                        val after = captureBodyText()
                        buildJsonObject {
                            put("success", true)
                            put("post_click_url", postUrl)
                            put("page_title", postTitle)
                            put("diff", BrowserDiffHelper.computeDiff(before, after))
                        }
                    }
                    else -> {
                        val text = if (mode == "readability" || mode == "auto") webView.runReadability() else null
                        val (resolved, extractMode) = if (!text.isNullOrEmpty() && (mode != "auto" || text.length >= READABILITY_MIN_CHARS)) {
                            text to "readability"
                        } else if (mode == "readability") {
                            return@withController buildJsonObject {
                                put("success", false); put("error", "readability_failed")
                            }
                        } else {
                            val rawJs = """(function(){
                                try {
                                    var t = (document.body.innerText || document.body.textContent || '').replace(/\s+/g,' ').trim();
                                    return JSON.stringify({text:t});
                                } catch(e) { return JSON.stringify({error:'js_failed'}); }
                            })()"""
                            val rawRes = parseJsResult(webView.evaluateJavascriptAsync(rawJs))
                            (rawRes["text"]?.jsonPrimitive?.contentOrNull.orEmpty()) to
                                (if (mode == "auto") "raw_fallback" else "raw")
                        }
                        val (clipped, truncated) = clipText(resolved, maxChars)
                        buildJsonObject {
                            put("success", true); put("post_click_url", postUrl); put("page_title", postTitle)
                            put("text", clipped); put("truncated", truncated); put("extract_mode", extractMode)
                        }
                    }
                }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.CLICK_AND_READ)
        textPart(out)
    },
)

// ---- Loop control --------------------------------------------------------------------------

fun browserDoneTool(callerConvId: () -> String): Tool = Tool(
    name = BrowserToolDefaults.DONE,
    description = "Signal that the AI has finished its browser task. Clears the per-task timer. The session stays alive for subsequent turns.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("summary", buildJsonObject { put("type", "string"); put("description", "One-sentence summary of what was accomplished") })
            put("result_url", buildJsonObject { put("type", "string"); put("description", "Optional URL to show") })
        }, required = listOf("summary"))
    },
    execute = { input ->
        val summary = input.jsonObject["summary"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val out = if (summary == null) missingArgEnvelope("summary", "summary is required")
        else {
            BrowserController.appendAction("Done: $summary")
            BrowserController.clearTaskWindow()
            BrowserController.unbindHeadless(callerConvId())
            HeadlessBrowserSessionPool.release(callerConvId())
            buildJsonObject { put("success", true) }
        }
        textPart(out)
    },
)

// ---- Internal helpers ---------------------------------------------------------------------

private fun selectorWithFullSchema(description: String): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("selector", buildJsonObject { put("type", "string"); put("description", description) })
        put("full", buildJsonObject { put("type", "boolean"); put("description", "If true, skip page-text diff (default false)") })
    },
    required = listOf("selector"),
)

private fun selectorAndMaxCharsSchema(defaultMax: Int, required: Boolean): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("selector", buildJsonObject { put("type", "string"); put("description", "CSS selector (default 'body')") })
        put("max_chars", buildJsonObject { put("type", "integer"); put("description", "Truncation cap (default $defaultMax)") })
        put("wait_ready", buildJsonObject { put("type", "boolean"); put("description", "Wait for in-flight navigation to settle before reading (default true)") })
    },
    required = if (required) listOf("selector") else null,
)

private fun getTextSchema(defaultMax: Int): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("selector", buildJsonObject { put("type", "string"); put("description", "Optional CSS selector — overrides Readability and reads the selector's innerText directly") })
        put("max_chars", buildJsonObject { put("type", "integer"); put("description", "Truncation cap (default $defaultMax)") })
        put("extract_mode", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add("auto"); add("readability"); add("raw") })
            put("description", "auto (default) tries Readability then falls back; readability forces it; raw uses selector-based innerText")
        })
        put("wait_ready", buildJsonObject { put("type", "boolean"); put("description", "Wait for in-flight navigation to settle before reading (default true)") })
    },
)

private suspend fun BrowserControllerHandle.WithControllerScope.captureBodyText(): String {
    val raw = webView.evaluateJavascriptAsync(
        "(function(){try{return JSON.stringify(document.body.innerText||'');}catch(e){return JSON.stringify('');}})()", 4_000L,
    ) ?: return ""
    return runCatching {
        val outer = Json.parseToJsonElement(raw)
        val inner = if (outer is JsonPrimitive && outer.isString) outer.contentOrNull.orEmpty() else outer.toString()
        Json.parseToJsonElement(inner).jsonPrimitive.contentOrNull.orEmpty()
    }.getOrElse { "" }
}

private suspend fun BrowserControllerHandle.WithControllerScope.withDiff(
    full: Boolean,
    action: suspend BrowserControllerHandle.WithControllerScope.() -> JsonObject,
): JsonObject {
    if (full) return action()
    val before = captureBodyText()
    val result = action()
    if (result.containsKey("error")) return result
    val after = captureBodyText()
    return buildJsonObject { result.forEach { (k, v) -> put(k, v) }; put("diff", BrowserDiffHelper.computeDiff(before, after)) }
}

private fun parseFullArg(input: kotlinx.serialization.json.JsonElement): Boolean =
    input.jsonObject["full"]?.jsonPrimitive?.booleanOrNull == true

private fun clickElementJs(selector: String): String = """(function(){
    try {
        var el = document.querySelector(${jsString(selector)});
        if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
        el.scrollIntoView({block:'center', inline:'center'});
        el.click();
        return JSON.stringify({clicked:true});
    } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
})()"""

private fun parseJsResult(raw: String?): JsonObject {
    if (raw == null) return buildJsonObject { put("error", "js_no_result") }
    return runCatching {
        val outer = Json.parseToJsonElement(raw)
        val inner = if (outer is JsonPrimitive && outer.isString) outer.contentOrNull.orEmpty() else outer.toString()
        Json.parseToJsonElement(inner).jsonObject
    }.getOrElse { buildJsonObject { put("error", "js_parse_failed"); put("raw", raw) } }
}

private suspend fun runReadHelper(
    input: kotlinx.serialization.json.JsonElement,
    toolName: String,
    defaultMax: Int,
    jsBuilder: (String, Int) -> String,
): JsonObject {
    val selector = (input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }) ?: "body"
    val maxChars = (input.jsonObject["max_chars"]?.jsonPrimitive?.intOrNull ?: defaultMax).coerceIn(100, 64 * 1024)
    val waitReady = input.jsonObject["wait_ready"]?.jsonPrimitive?.booleanOrNull ?: true
    return withTimeoutOrNull(toolTimeoutMs) {
        BrowserControllerHandle.withController {
            val tracker = controller.activeTracker()
            if (waitReady) webView.awaitPageIdle(tracker, 5_000L)
            val snap = webView.readPageSnapshot()
            val res = parseJsResult(webView.evaluateJavascriptAsync(jsBuilder(selector, maxChars)))
            finalizeReadResult(res, snap, tracker)
        }
    } ?: timeoutEnvelope(toolName)
}

private const val READABILITY_MIN_CHARS = 200

private suspend fun runGetText(input: kotlinx.serialization.json.JsonElement): JsonObject {
    val explicitSelector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val maxChars = (input.jsonObject["max_chars"]?.jsonPrimitive?.intOrNull ?: 8000).coerceIn(100, 64 * 1024)
    val mode = input.jsonObject["extract_mode"]?.jsonPrimitive?.contentOrNull?.lowercase()
        ?.takeIf { it in setOf("auto", "readability", "raw") } ?: "auto"
    val waitReady = input.jsonObject["wait_ready"]?.jsonPrimitive?.booleanOrNull ?: true
    return withTimeoutOrNull(toolTimeoutMs) {
        BrowserControllerHandle.withController {
            val tracker = controller.activeTracker()
            if (waitReady) webView.awaitPageIdle(tracker, 5_000L)
            val snap = webView.readPageSnapshot()
            val res = if (explicitSelector != null) {
                runRawText(explicitSelector, maxChars, mode = "raw_selector")
            } else when (mode) {
                "raw" -> runRawText("body", maxChars, mode = "raw")
                "readability" -> {
                    val text = webView.runReadability()
                    if (text.isNullOrEmpty()) buildJsonObject { put("error", "readability_failed"); put("recovery", "Try extract_mode:'auto'") }
                    else buildJsonObject {
                        val (clipped, truncated) = clipText(text, maxChars)
                        put("text", clipped); put("truncated", truncated); put("extract_mode", "readability")
                    }
                }
                else -> {
                    val text = webView.runReadability()
                    if (!text.isNullOrEmpty() && text.length >= READABILITY_MIN_CHARS) {
                        val (clipped, truncated) = clipText(text, maxChars)
                        buildJsonObject { put("text", clipped); put("truncated", truncated); put("extract_mode", "readability") }
                    } else runRawText("body", maxChars, mode = "raw_fallback")
                }
            }
            finalizeReadResult(res, snap, tracker)
        }
    } ?: timeoutEnvelope(BrowserToolDefaults.GET_TEXT)
}

private suspend fun BrowserControllerHandle.WithControllerScope.runRawText(selector: String, maxChars: Int, mode: String): JsonObject {
    val js = """(function(){
        try {
            var el = document.querySelector(${jsString(selector)});
            if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
            var t = (el.innerText || el.textContent || '').replace(/\s+/g,' ').trim();
            var truncated = false;
            if (t.length > $maxChars) { t = t.substring(0, $maxChars); truncated = true; }
            return JSON.stringify({text:t, truncated:truncated});
        } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
    })()"""
    val res = parseJsResult(webView.evaluateJavascriptAsync(js))
    return if (res.containsKey("error")) res else buildJsonObject { res.forEach { (k, v) -> put(k, v) }; put("extract_mode", mode) }
}

private fun clipText(text: String, maxChars: Int): Pair<String, Boolean> =
    if (text.length <= maxChars) text to false else text.substring(0, maxChars) to true

private suspend fun runHistoryNav(toolName: String, forward: Boolean, input: kotlinx.serialization.json.JsonElement): JsonObject {
    val throwOnError = input.jsonObject["throw_on_error"]?.jsonPrimitive?.booleanOrNull == true
    val out = withTimeoutOrNull(toolTimeoutMs) {
        BrowserControllerHandle.withController {
            val tracker = controller.activeTracker()
            val canGo = if (forward) webView.canGoForward() else webView.canGoBack()
            if (!canGo) {
                return@withController buildJsonObject {
                    put("success", false)
                    put("error", "NO_HISTORY")
                    put("detail", if (forward) "No forward entry in browser history." else "No back entry in browser history.")
                }
            }
            val outcome = webView.navigateAndSettle(tracker, timeoutMs = 12_000L) {
                if (forward) webView.goForward() else webView.goBack()
            }
            val snap = webView.readPageSnapshot()
            if (outcome.failed) {
                val env = navFailureEnvelope(outcome, snap)
                if (throwOnError) throw IllegalStateException("$toolName failed: ${env["error"]} (${snap.url})")
                env
            } else {
                BrowserController.appendAction(if (forward) "Forward" else "Back")
                buildJsonObject {
                    put("success", true)
                    put("current_url", snap.url)
                    put("page_title", snap.title)
                }
            }
        }
    } ?: timeoutEnvelope(toolName)
    return out
}

fun createBrowserTool(
    toolName: String,
    context: Context,
    convIdProvider: () -> String,
    invocationContext: ToolInvocationContext? = null,
): Tool? = when (toolName) {
    BrowserToolDefaults.OPEN -> browserOpenTool(context, convIdProvider)
    BrowserToolDefaults.CURRENT_URL -> browserCurrentUrlTool()
    BrowserToolDefaults.SCREENSHOT -> browserScreenshotTool(context, invocationContext ?: ToolInvocationContext.EMPTY)
    BrowserToolDefaults.GET_TEXT -> browserGetTextTool()
    BrowserToolDefaults.GET_DOM -> browserGetDomTool()
    BrowserToolDefaults.GET_LINKS -> browserGetLinksTool()
    BrowserToolDefaults.BACK -> browserBackTool()
    BrowserToolDefaults.FORWARD -> browserForwardTool()
    BrowserToolDefaults.WAIT_FOR -> browserWaitForTool()
    BrowserToolDefaults.WAIT_FOR_LOAD -> browserWaitForLoadTool()
    BrowserToolDefaults.CLICK -> browserClickTool()
    BrowserToolDefaults.TYPE -> browserTypeTool()
    BrowserToolDefaults.SCROLL -> browserScrollTool()
    BrowserToolDefaults.SUBMIT -> browserSubmitTool()
    BrowserToolDefaults.SELECT -> browserSelectTool()
    BrowserToolDefaults.PRESS_KEY -> browserPressKeyTool()
    BrowserToolDefaults.EVAL_JS -> browserEvalJsTool()
    BrowserToolDefaults.CLICK_AND_READ -> browserClickAndReadTool()
    BrowserToolDefaults.DONE -> browserDoneTool(convIdProvider)
    else -> null
}
