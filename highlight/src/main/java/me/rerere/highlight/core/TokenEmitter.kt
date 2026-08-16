package me.rerere.highlight.core


/* ───【原版对齐】TokenEmitter.kt | 差异 ±0 行
 * 来源: 原版移植 + 自研小调整 (未达专项标注阈值, 对齐细节见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import me.rerere.highlight.HighlightToken

/**
 * Collects highlighted text into a flat list of [HighlightToken].
 *
 * `highlight.js` builds a tree of nested scopes and renders it as nested `<span>` elements. A flat
 * token list cannot express nesting, so the innermost scope wins: it is the one a browser would
 * paint last. Sub-language markers (`language:xxx`) carry no colour of their own and are therefore
 * transparent, letting the surrounding scope show through.
 */
internal class TokenEmitter {
    private val tokens = mutableListOf<HighlightToken>()
    private val scopes = ArrayDeque<String>()

    fun addText(text: String) {
        append(text, scopes.lastOrNull())
    }

    fun startScope(scope: String) {
        scopes.addLast(scope)
    }

    fun endScope() {
        scopes.removeLastOrNull()
    }

    /** Splices the tokens of a sub-language, letting unscoped text inherit the host scope. */
    fun addSublanguage(childTokens: List<HighlightToken>) {
        childTokens.forEach { token ->
            when (token) {
                is HighlightToken.Plain -> addText(token.content)
                is HighlightToken.Styled -> append(token.content, token.type)
            }
        }
    }

    fun finalize() {
        scopes.clear()
    }

    fun build(): List<HighlightToken> = tokens.toList()

    private fun append(content: String, scope: String?) {
        if (content.isEmpty()) return

        val previous = tokens.lastOrNull()
        when {
            scope == null && previous is HighlightToken.Plain ->
                tokens[tokens.lastIndex] = HighlightToken.Plain(previous.content + content)

            scope == null ->
                tokens += HighlightToken.Plain(content)

            previous is HighlightToken.Styled && previous.type == scope ->
                tokens[tokens.lastIndex] = HighlightToken.Styled(previous.content + content, scope)

            else ->
                tokens += HighlightToken.Styled(content, scope)
        }
    }
}
