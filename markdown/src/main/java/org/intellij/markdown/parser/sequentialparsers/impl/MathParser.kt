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
        val nextType = dollarIterator.rawLookup(1)
        if (nextType == null) return false
        // 原行为（保持不变）：`$` 后紧跟非空白 → 照旧识别。
        // 这样 `$a$` / `其中$x$为解` / `$100$` 等既有用例一个都不受影响。
        if (!isWhitespace(nextType)) return true

        // v5.3.1 新增分支（用户实测 `$ \int_{-y}^{y}\frac{1}{3}dx=\frac{2y}{3}$` 不渲染）：
        // `$` 后跟空白时，跨过少量空白，要求首个非空白字符是 LaTeX 特征
        // （\ ^ _ {）—— 命中带前导空格的公式，同时不把「价格 $ 100 元」误判。
        var offset = 2
        var skipped = 0
        while (skipped <= 8) {
            val ch = dollarIterator.charLookup(offset) ?: return false
            if (ch == '\n' || ch == '\r') return false
            if (ch.isWhitespace()) {
                offset++
                skipped++
                continue
            }
            return ch == '\\' || ch == '^' || ch == '_' || ch == '{'
        }
        return false
    }

    private fun canCloseInlineMath(dollarIterator: TokensCache.Iterator): Boolean {
        val prevType = dollarIterator.rawLookup(-1)
        if (prevType == null || isWhitespace(prevType)) return false
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
