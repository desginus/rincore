package me.rerere.rikkahub.browser

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object BrowserDiffHelper {
    const val MAX_CHARS_PER_SIDE = 2000

    fun computeDiff(before: String, after: String): JsonObject {
        if (before == after) return buildJsonObject { put("unchanged", true) }
        val beforeLines = LinkedHashSet(before.split('\n'))
        val afterLines = LinkedHashSet(after.split('\n'))
        val addedLines = afterLines.filterNot { it in beforeLines }
        val removedLines = beforeLines.filterNot { it in afterLines }
        val rawAdded = addedLines.joinToString("\n").trim()
        val rawRemoved = removedLines.joinToString("\n").trim()
        if (rawAdded.isEmpty() && rawRemoved.isEmpty()) return buildJsonObject { put("unchanged", true) }
        val (added, addedTruncated) = truncate(rawAdded)
        val (removed, removedTruncated) = truncate(rawRemoved)
        return buildJsonObject {
            put("added", added); put("removed", removed)
            put("added_chars", added.length); put("removed_chars", removed.length)
            put("truncated", addedTruncated || removedTruncated)
        }
    }

    private fun truncate(s: String): Pair<String, Boolean> =
        if (s.length <= MAX_CHARS_PER_SIDE) s to false else s.substring(0, MAX_CHARS_PER_SIDE) to true
}
