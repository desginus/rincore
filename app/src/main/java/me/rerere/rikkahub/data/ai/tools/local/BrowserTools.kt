package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import me.rerere.rikkahub.browser.BrowserToolDefaults
import me.rerere.rikkahub.browser.HeadlessBrowserSessionPool
import me.rerere.rikkahub.browser.ReadabilityRunner.runReadability
import me.rerere.rikkahub.browser.awaitReadyState
import me.rerere.rikkahub.browser.evaluateJavascriptAsync
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

private fun textPart(obj: JsonObject): List<UIMessagePart> =
    listOf(UIMessagePart.Text(Json.encodeToString(obj)))

private fun jsString(s: String): String = JsonPrimitive(s).toString()

// ---- Read tools --------------------------------------------------------------------------

fun browserOpenTool(context: Context): Tool = Tool(
    name = BrowserToolDefaults.OPEN,
    description = "Open a URL in the headless browser. Returns the URL and page title after navigation completes. If a previous session exists for this conversation, the page loads in that session (preserving cookies and history).",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("url", buildJsonObject {
                put("type", "string")
                put("description", "Fully-qualified URL to navigate to (must start with http:// or https://)")
            })
        }, required = listOf("url"))
    },
    execute = { input ->
        val url = input.jsonObject["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        if (url == null || !url.startsWith("http")) {
            return@Tool textPart(missingArgEnvelope("url", "url must be a valid http/https URL"))
        }
        val convId = BrowserController.currentUrl().orEmpty().ifBlank { "default" }
        val session = HeadlessBrowserSessionPool.getOrCreate(context, convId)
        val webView = session.start(convId)
        if (!BrowserController.bindHeadless(convId, webView)) {
            return@Tool textPart(BrowserController.bindBusyEnvelope())
        }
        BrowserController.startTaskWindow()
        BrowserCacheSweeper.sweep(context)
        val out = withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                withContext(Dispatchers.Main) { webView.loadUrl(url) }
                webView.awaitReadyState(12_000L)
                BrowserController.appendAction("Open: $url")
                buildJsonObject {
                    put("success", true)
                    put("current_url", webView.url.orEmpty())
                    put("page_title", webView.title.orEmpty())
                }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.OPEN)
        textPart(out)
    },
)

fun browserCurrentUrlTool(): Tool = Tool(
    name = BrowserToolDefaults.CURRENT_URL,
    description = "Return the browser's current URL and page title.",
    execute = {
        val out = BrowserControllerHandle.withController {
            buildJsonObject {
                put("current_url", webView.url.orEmpty())
                put("page_title", webView.title.orEmpty())
            }
        }
        textPart(out)
    },
)

fun browserScreenshotTool(context: Context): Tool = Tool(
    name = BrowserToolDefaults.SCREENSHOT,
    description = "Capture a full-viewport screenshot of the current page as a PNG. Returns the absolute file path of the saved image.",
    execute = {
        val out = withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                val w = webView.width.coerceAtLeast(1)
                val h = webView.height.coerceAtLeast(1).coerceAtMost(MAX_SCREENSHOT_HEIGHT_PX)
                val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                try {
                    val canvas = android.graphics.Canvas(bitmap)
                    webView.draw(canvas)
                    val cacheDir = File(context.cacheDir, SCREENSHOT_CACHE_SUBDIR).apply { mkdirs() }
                    val outFile = File(cacheDir, "shot-${System.currentTimeMillis()}.png")
                    FileOutputStream(outFile).use { os ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os)
                    }
                    BrowserController.appendAction("Screenshot")
                    buildJsonObject {
                        put("success", true)
                        put("path", outFile.absolutePath)
                        put("width", w)
                        put("height", h)
                    }
                } finally { bitmap.recycle() }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.SCREENSHOT)
        textPart(out)
    },
)

fun browserGetTextTool(): Tool = Tool(
    name = BrowserToolDefaults.GET_TEXT,
    description = "Extract readable text from the current page. extract_mode: 'auto' (default, tries Readability then falls back to body.innerText), 'readability' (forces article extraction), 'raw' (selector-based innerText). Optional selector overrides Readability.",
    parameters = { getTextSchema(8000) },
    execute = { input -> textPart(runGetText(input)) },
)

fun browserGetDomTool(): Tool = Tool(
    name = BrowserToolDefaults.GET_DOM,
    description = "Extract the outerHTML of an element matching a CSS selector. Defaults to 'body'. Clamped to max_chars (default 4000).",
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
    description = "List all <a href> links on the page with their text content. Caps at 200 links.",
    execute = {
        val out = withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
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
                val raw = webView.evaluateJavascriptAsync(js)
                parseJsResult(raw)
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.GET_LINKS)
        textPart(out)
    },
)

fun browserBackTool(): Tool = Tool(
    name = BrowserToolDefaults.BACK,
    description = "Navigate back in browser history. Returns {success, current_url}.",
    execute = { input -> textPart(runHistoryNav(BrowserToolDefaults.BACK, forward = false)) },
)

fun browserForwardTool(): Tool = Tool(
    name = BrowserToolDefaults.FORWARD,
    description = "Navigate forward in browser history. Returns {success, current_url}.",
    execute = { input -> textPart(runHistoryNav(BrowserToolDefaults.FORWARD, forward = true)) },
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

// ---- Write tools --------------------------------------------------------------------------

fun browserClickTool(): Tool = Tool(
    name = BrowserToolDefaults.CLICK,
    description = "Click an element matching a CSS selector. Returns diff ({added, removed, added_chars, removed_chars, truncated}) by default. Pass full:true to skip diff and return post_click_url only.",
    parameters = { selectorWithFullSchema("CSS selector to click") },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val full = parseFullArg(input)
        val out = if (selector == null) missingArgEnvelope("selector", "selector is required")
        else withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                withDiff(full) {
                    val js = """(function(){
                        try {
                            var el = document.querySelector(${jsString(selector)});
                            if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
                            el.scrollIntoView({block:'center', inline:'center'});
                            el.click();
                            return JSON.stringify({clicked:true});
                        } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                    })()"""
                    val raw = webView.evaluateJavascriptAsync(js)
                    val res = parseJsResult(raw)
                    if (res.containsKey("error")) return@withDiff res
                    webView.awaitReadyState(8_000L)
                    BrowserController.appendAction("Click: $selector")
                    buildJsonObject { put("success", true); put("post_click_url", webView.url.orEmpty()) }
                }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.CLICK)
        textPart(out)
    },
)

fun browserTypeTool(): Tool = Tool(
    name = BrowserToolDefaults.TYPE,
    description = "Type text into an input/textarea/contenteditable element. Focuses, optionally clears, sets the value + dispatches 'input' event. Returns diff by default; pass full:true to skip.",
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
                                if ($clearFlag) {
                                    if ('value' in el) el.value = '';
                                    else if (el.isContentEditable) el.textContent = '';
                                }
                                if ('value' in el) el.value = (el.value || '') + ${jsString(text)};
                                else if (el.isContentEditable) el.textContent = (el.textContent || '') + ${jsString(text)};
                                el.dispatchEvent(new Event('input', {bubbles:true}));
                                el.dispatchEvent(new Event('change', {bubbles:true}));
                                return JSON.stringify({typed:true});
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
    description = "Submit a form. If selector is a <button type=submit>, click it; otherwise locates the enclosing <form> and calls .submit(). Returns diff by default; pass full:true to skip.",
    parameters = { selectorWithFullSchema("CSS selector of a submit button or element inside the target form") },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val full = parseFullArg(input)
        val out = if (selector == null) missingArgEnvelope("selector", "selector is required")
        else withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                withDiff(full) {
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
                    if (res.containsKey("error")) return@withDiff res
                    webView.awaitReadyState(8_000L)
                    BrowserController.appendAction("Submit: $selector")
                    buildJsonObject { put("success", true); put("post_submit_url", webView.url.orEmpty()) }
                }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.SUBMIT)
        textPart(out)
    },
)

fun browserSelectTool(): Tool = Tool(
    name = BrowserToolDefaults.SELECT,
    description = "Set a <select> element's value. Dispatches 'change' event. Returns diff by default; pass full:true to skip.",
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
                                el.value = ${jsString(value)};
                                el.dispatchEvent(new Event('change', {bubbles:true}));
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
    description = "One-shot click + read. Click an element, await readyState, return diff (default) or extracted text. extract_mode: diff (default), auto, readability, raw. max_chars caps text (default 4000).",
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
                val before = if (mode == "diff") captureBodyText() else ""
                val clickJs = """(function(){
                    try {
                        var el = document.querySelector(${jsString(selector)});
                        if (!el) return JSON.stringify({error:'selector_not_found'});
                        el.scrollIntoView({block:'center', inline:'center'});
                        el.click();
                        return JSON.stringify({clicked:true});
                    } catch(e) { return JSON.stringify({error:'js_failed'}); }
                })()"""
                val clickRes = parseJsResult(webView.evaluateJavascriptAsync(clickJs))
                if (clickRes.containsKey("error")) return@withController clickRes
                webView.awaitReadyState(8_000L)
                BrowserController.appendAction("Click+read: $selector")
                val postUrl = withContext(Dispatchers.Main) { webView.url.orEmpty() }
                val postTitle = withContext(Dispatchers.Main) { webView.title.orEmpty() }
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

fun browserDoneTool(): Tool = Tool(
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
    return withTimeoutOrNull(toolTimeoutMs) {
        BrowserControllerHandle.withController { parseJsResult(webView.evaluateJavascriptAsync(jsBuilder(selector, maxChars))) }
    } ?: timeoutEnvelope(toolName)
}

private const val READABILITY_MIN_CHARS = 200

private suspend fun runGetText(input: kotlinx.serialization.json.JsonElement): JsonObject {
    val explicitSelector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val maxChars = (input.jsonObject["max_chars"]?.jsonPrimitive?.intOrNull ?: 8000).coerceIn(100, 64 * 1024)
    val mode = input.jsonObject["extract_mode"]?.jsonPrimitive?.contentOrNull?.lowercase()
        ?.takeIf { it in setOf("auto", "readability", "raw") } ?: "auto"
    return withTimeoutOrNull(toolTimeoutMs) {
        BrowserControllerHandle.withController {
            if (explicitSelector != null) return@withController runRawText(explicitSelector, maxChars, mode = "raw_selector")
            when (mode) {
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

private suspend fun runHistoryNav(toolName: String, forward: Boolean): JsonObject {
    val out = withTimeoutOrNull(toolTimeoutMs) {
        BrowserControllerHandle.withController {
            val ok = withContext(Dispatchers.Main) {
                if (forward) { if (webView.canGoForward()) { webView.goForward(); true } else false }
                else { if (webView.canGoBack()) { webView.goBack(); true } else false }
            }
            if (ok) webView.awaitReadyState(8_000L)
            BrowserController.appendAction(if (forward) "Forward" else "Back")
            buildJsonObject { put("success", ok); put("current_url", webView.url.orEmpty()) }
        }
    } ?: timeoutEnvelope(toolName)
    return out
}

fun createBrowserTool(toolName: String, context: Context): Tool? = when (toolName) {
    BrowserToolDefaults.OPEN -> browserOpenTool(context)
    BrowserToolDefaults.CURRENT_URL -> browserCurrentUrlTool()
    BrowserToolDefaults.SCREENSHOT -> browserScreenshotTool(context)
    BrowserToolDefaults.GET_TEXT -> browserGetTextTool()
    BrowserToolDefaults.GET_DOM -> browserGetDomTool()
    BrowserToolDefaults.GET_LINKS -> browserGetLinksTool()
    BrowserToolDefaults.BACK -> browserBackTool()
    BrowserToolDefaults.FORWARD -> browserForwardTool()
    BrowserToolDefaults.WAIT_FOR -> browserWaitForTool()
    BrowserToolDefaults.CLICK -> browserClickTool()
    BrowserToolDefaults.TYPE -> browserTypeTool()
    BrowserToolDefaults.SCROLL -> browserScrollTool()
    BrowserToolDefaults.SUBMIT -> browserSubmitTool()
    BrowserToolDefaults.SELECT -> browserSelectTool()
    BrowserToolDefaults.PRESS_KEY -> browserPressKeyTool()
    BrowserToolDefaults.EVAL_JS -> browserEvalJsTool()
    BrowserToolDefaults.CLICK_AND_READ -> browserClickAndReadTool()
    BrowserToolDefaults.DONE -> browserDoneTool()
    else -> null
}
