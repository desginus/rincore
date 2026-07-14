package me.rerere.rikkahub.browser

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Readability.js bridge for browser_get_text.
 *
 * Injects Mozilla's Readability.js (Apache-2.0) from assets/browser/readability.js
 * into the page to extract main-article content from a cloned document.
 * Hard timeout via withTimeoutOrNull — bounded so the calling tool never exceeds
 * its own 30-s tool budget.
 */
object ReadabilityRunner {

    private const val ASSET_PATH = "browser/readability.js"

    @Volatile
    private var cachedLibrary: String? = null

    suspend fun WebView.runReadability(timeoutMs: Long = 4_000L): String? {
        val context = this.context.applicationContext ?: return null
        val library = loadLibrary(context) ?: return null
        return withTimeoutOrNull(timeoutMs) {
            val payload = """(function(){
                try {
                    if (typeof Readability === 'undefined') { $library }
                    var doc = document.cloneNode(true);
                    var article = new Readability(doc).parse();
                    if (!article) return JSON.stringify(null);
                    return JSON.stringify({
                        textContent: (article.textContent || '').replace(/\s+/g,' ').trim(),
                        title: article.title || '',
                        byline: article.byline || '',
                        siteName: article.siteName || '',
                        length: (article.textContent || '').length
                    });
                } catch(e) { return JSON.stringify({error: String(e)}); }
            })()"""
            val raw = this@runReadability.evaluateJavascriptAsync(payload, timeoutMs)
            parseTextContent(raw)
        }
    }

    private fun loadLibrary(context: Context): String? {
        cachedLibrary?.let { return it }
        return runCatching {
            context.assets.open(ASSET_PATH).use { stream ->
                BufferedReader(InputStreamReader(stream)).use { it.readText() }
            }
        }.onSuccess { cachedLibrary = it }
            .onFailure { android.util.Log.w("ReadabilityRunner", "asset load failed", it) }
            .getOrNull()
    }

    private fun parseTextContent(raw: String?): String? {
        if (raw == null || raw == "null" || raw == "\"null\"") return null
        return runCatching {
            val outer = Json.parseToJsonElement(raw)
            val inner = if (outer is JsonPrimitive && outer.isString) outer.contentOrNull.orEmpty()
            else outer.toString()
            val element = Json.parseToJsonElement(inner)
            if (element is JsonPrimitive && element.contentOrNull == null) return@runCatching null
            val obj = element.jsonObject
            if (obj.containsKey("error")) return@runCatching null
            obj["textContent"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
