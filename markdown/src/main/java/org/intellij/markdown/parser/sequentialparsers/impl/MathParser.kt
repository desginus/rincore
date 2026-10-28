package org.intellij.markdown.parser.sequentialparsers.impl

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.sequentialparsers.RangesListBuilder
import org.intellij.markdown.parser.sequentialparsers.SequentialParser
import org.intellij.markdown.parser.sequentialparsers.TokensCache

class MathParser : SequentialParser {
    override fun parse(tokens: TokensCache, rangesToGlue: List<IntRange>): SequentialParser.ParsingResult {
        val result = SequentialParser.ParsingResultBuilder()
        val delegateIndices = RangesListBuilder()
        var iterator: TokensCache.Iterator = tokens.RangesListIterator(rangesToGlue)

        while (iterator.type != null) {
            if (iterator.type == GFMTokenTypes.DOLLAR) {
                val isBlock = iterator.length >= 2

                if (!isBlock && !canOpenInlineMath(iterator)) {
                    delegateIndices.put(iterator.index)
                    iterator = iterator.advance()
                    continue
                }

                val endIterator = findClosingDollar(iterator.advance(), iterator.length, isBlock)

                if (endIterator != null) {
                    if (isBlock) {
                        result.withNode(SequentialParser.Node(iterator.index..endIterator.index + 1, GFMElementTypes.BLOCK_MATH))
                    } else {
                        result.withNode(SequentialParser.Node(iterator.index..endIterator.index + 1, GFMElementTypes.INLINE_MATH))
                    }
                    iterator = endIterator.advance()
                    continue
                }
            }
            delegateIndices.put(iterator.index)
            iterator = iterator.advance()
        }

        return result.withFurtherProcessing(delegateIndices.get())
    }

    private fun canOpenInlineMath(dollarIterator: TokensCache.Iterator): Boolean {
        // v5.3.1 ★用户实测：`$ \int_{-y}^{y}\frac{1}{3}dx=\frac{2y}{3}$` 整段不渲染。
        // 旧规则要求 `$` 后**紧跟非空白**（GFM 原规范），而这段在 `$` 后有个空格 →
        // canOpenInlineMath=false → 完全不识别为公式 → 原样当正文显示。
        // 现在：允许 `$` 后跨过少量空白，但**首个非空白字符必须是 LaTeX 特征**
        // （\ ^ _ { ( [），既能命中用户这种带空格的公式，又不会把「价格 $ 100 元」误判。
        var offset = 1
        var skipped = 0
        while (skipped <= 8) {
            val ch = dollarIterator.charLookup(offset) ?: return false
            if (ch == '\n' || ch == '\r') return false
            if (ch.isWhitespace()) {
                offset++
                skipped++
                continue
            }
            return ch == '\\' || ch == '^' || ch == '_' || ch == '{' || ch == '(' || ch == '['
        }
        return false
    }

    private fun canCloseInlineMath(dollarIterator: TokensCache.Iterator): Boolean {
        // v5.3.1：允许 `$` 前是空白（`... \frac{2y}{3} $` 这种写法同样要认），
        // 仅保留「`$` 后面紧跟数字则不是闭合」以避开 $100 这类货币写法。
        val charAfter = dollarIterator.charLookup(1)
        if (charAfter in '0'..'9') return false
        return true
    }

    private fun isWhitespace(type: IElementType): Boolean {
        return type == MarkdownTokenTypes.WHITE_SPACE
                || type == MarkdownTokenTypes.EOL
                || type == MarkdownTokenTypes.HARD_LINE_BREAK
    }

    private fun findClosingDollar(it: TokensCache.Iterator, length: Int, isBlock: Boolean): TokensCache.Iterator? {
        var iterator = it
        var hasContent = false
        while (iterator.type != null) {
            if (iterator.type == GFMTokenTypes.DOLLAR) {
                if (iterator.length == length && hasContent && (isBlock || canCloseInlineMath(iterator))) {
                    return iterator
                }
                if (!isBlock && iterator.length <= length) {
                    return null
                }
            } else {
                hasContent = true
            }
            iterator = iterator.advance()
        }
        return null
    }
}
