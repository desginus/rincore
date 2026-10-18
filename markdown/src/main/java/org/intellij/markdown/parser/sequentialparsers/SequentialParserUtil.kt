package org.intellij.markdown.parser.sequentialparsers

import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.html.isPunctuation
import org.intellij.markdown.html.isWhitespace

class SequentialParserUtil {
    companion object {
        

        fun isWhitespace(info: TokensCache.Iterator, lookup: Int): Boolean {
            return isWhitespace(info.charLookup(lookup))
        }

        fun isPunctuation(info: TokensCache.Iterator, lookup: Int): Boolean {
            return isPunctuation(info.charLookup(lookup))
        }

        // RinCore vendored 补丁 (源: rikkahub/markdown 501ce95c64):
        // CJK 字符作为 flanking 词边界 — 修复 **"中文"** 类强调在标点旁失效
        fun isCJK(info: TokensCache.Iterator, lookup: Int): Boolean {
            val char = info.charLookup(lookup)
            return char in '\u2E80'..'\u9FFF' ||
                   char in '\uAC00'..'\uD7AF' ||
                   char in '\uF900'..'\uFAFF'
        }

       fun filterBlockquotes(tokensCache: TokensCache, textRange: IntRange): List<IntRange> {
            val result = ArrayList<IntRange>()
            var lastStart = textRange.first

            val R = textRange.last
            for (i in lastStart..R - 1) {
                if (tokensCache.Iterator(i).type == MarkdownTokenTypes.BLOCK_QUOTE) {
                    if (lastStart < i) {
                        result.add(lastStart..i - 1)
                    }
                    lastStart = i + 1
                }
            }
            if (lastStart < R) {
                result.add(lastStart..R)
            }
            return result
        }
    }

}

class RangesListBuilder {
    private val list = ArrayList<IntRange>()
    private var lastStart = -239
    private var lastEnd = -239

    fun put(index: Int) {
        if (lastEnd + 1 == index) {
            lastEnd = index
            return
        }
        if (lastStart != -239) {
            list.add(lastStart..lastEnd)
        }
        lastStart = index
        lastEnd = index
    }

    fun get(): List<IntRange> {
        if (lastStart != -239) {
            list.add(lastStart..lastEnd)
        }
        lastStart = -239
        lastEnd = -239
        return list
    }

}
