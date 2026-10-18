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
        return nextType != null && !isWhitespace(nextType)
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
