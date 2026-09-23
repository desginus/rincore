package org.intellij.markdown.html

import java.net.URLEncoder

/* RinCore vendored (源: JetBrains/markdown 0.7.14 + rikkahub CJK 补丁 501ce95c64)
 * 原 multiplatform expect/actual 结构合并为 JVM 单源集实现。 */

typealias URI = java.net.URI

class BitSet(size: Int) : java.util.BitSet(size) {
    val size: Int = size()
}

fun URI.resolveToStringSafe(str: String): String {
    return try {
        resolve(str).toString()
    }
    catch (e: Throwable) {
        str
    }
}

inline fun BitSet.clear(index: Int) =
    set(index, false)

private const val PUNCTUATION_MASK: Int = (1 shl Character.DASH_PUNCTUATION.toInt()) or
        (1 shl Character.START_PUNCTUATION.toInt())     or
        (1 shl Character.END_PUNCTUATION.toInt())       or
        (1 shl Character.CONNECTOR_PUNCTUATION.toInt()) or
        (1 shl Character.OTHER_PUNCTUATION.toInt())     or
        (1 shl Character.INITIAL_QUOTE_PUNCTUATION.toInt()) or
        (1 shl Character.FINAL_QUOTE_PUNCTUATION.toInt()) or
        (1 shl Character.MATH_SYMBOL.toInt())

fun isWhitespace(char: Char): Boolean {
    return char == 0.toChar() || Character.isSpaceChar(char) || char.isWhitespace()
}

fun isPunctuation(char: Char): Boolean {
    return isAsciiPunctuationFix(char) || (PUNCTUATION_MASK shr Character.getType(char)) and 1 != 0
}

private fun isAsciiPunctuationFix(char: Char): Boolean {
    // the ones which are not covered by a more general check
    return "$^`".contains(char)
}

fun urlEncode(str: String): String {
    return URLEncoder.encode(str, "UTF-8")
}
