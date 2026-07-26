package me.rerere.rikkahub.data.ai.compression

/**
 * 通用文本压缩器 — 仅用于非搜索工具的长文本输出。
 * 保留首段 + 关键句 + 末段。
 */
object TextCompressor {
    private const val MIN_CHARS = 400
    private const val MAX_KEY = 6

    private val keyRe = Regex(
        """(error|warning|critical|important|注意|警告|错误|关键|重要|\b\d{2,}\b|https?://\S+)""",
        setOf(RegexOption.IGNORE_CASE)
    )

    fun compress(text: String): String? {
        if (text.length < MIN_CHARS) return null
        val paras = text.split(Regex("\n\n|\r\n\r\n")).map { it.trim() }.filter { it.isNotBlank() }
        if (paras.size <= 2) return compressBlock(text)
        return compressParas(paras)
    }

    private fun compressBlock(text: String): String? {
        val sentences = text.split(Regex("(?<=[.!?。！？])\\s*")).map { it.trim() }.filter { it.length > 5 }
        if (sentences.size <= 5) return null
        val keyIdx = sentences.indices.filter { keyRe.containsMatchIn(sentences[it]) }.take(MAX_KEY).toSet()
        val sb = StringBuilder()
        sb.appendLine(sentences.first()).appendLine()
        if (keyIdx.isNotEmpty()) { sb.appendLine("Key points:"); keyIdx.sorted().forEach { sb.appendLine("- ${sentences[it]}") }; sb.appendLine() }
        sb.append(sentences.last())
        return sb.toString()
    }

    private fun compressParas(paras: List<String>): String {
        val sb = StringBuilder()
        sb.appendLine(paras.first()).appendLine()
        val mid = paras.drop(1).dropLast(1)
        if (mid.isNotEmpty()) {
            val keys = mid.flatMap { p ->
                val s = p.split(Regex("(?<=[.!?。！？])\\s*")).map { it.trim() }.filter { it.length > 5 }
                s.filter { keyRe.containsMatchIn(it) }.take(2)
            }.take(MAX_KEY)
            if (keys.isNotEmpty()) { sb.appendLine("Key points:"); keys.forEach { sb.appendLine("- $it") }; sb.appendLine() }
        }
        sb.append(paras.last())
        return sb.toString()
    }
}
