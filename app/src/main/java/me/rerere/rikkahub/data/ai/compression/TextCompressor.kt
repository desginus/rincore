package me.rerere.rikkahub.data.ai.compression

/**
 * Headroom Kompress 风格的文本压缩器。
 *
 * 策略 (提取式摘要, 无 ML 模型):
 * - 保留首段 (通常包含主题)
 * - 保留末段 (通常包含结论)
 * - 中间: 提取关键句 (含数字/实体/引用的句子)
 * - 对极长文本: 额外提取每段首句
 */
object TextCompressor {
    private const val MIN_CHARS_TO_COMPRESS = 400
    private const val MAX_KEY_SENTENCES = 8

    private val keySentencePattern = Regex(
        """(contains|important|note|warning|error|key|critical|significant|"""
                + """must|should|required|necessary|一定要|必须|注意|警告|关键|重要|"""
                + """\b\d{2,}\b|"""
                + """[""''].+?[""'']|"""
                + """https?://\S+|"""
                + """\b[A-Z][a-z]+ [A-Z][a-z]+\b)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 压缩文本内容。如果文本太短则返回 null。
     */
    fun compress(text: String): String? {
        if (text.length < MIN_CHARS_TO_COMPRESS) return null

        val paragraphs = text.split(Regex("\n\n|\r\n\r\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (paragraphs.size <= 2) return compressSingleBlock(text)

        return compressMultiParagraph(paragraphs)
    }

    private fun compressSingleBlock(text: String): String? {
        val sentences = splitSentences(text)
        if (sentences.size <= 5) return null

        val keyIndices = findKeySentences(sentences)
        return buildCompressed(sentences, keyIndices, text.length)
    }

    private fun compressMultiParagraph(paragraphs: List<String>): String {
        val sb = StringBuilder()

        // 首段保留
        sb.appendLine(paragraphs.first())
        sb.appendLine()

        val middle = paragraphs.drop(1).dropLast(1)
        if (middle.isNotEmpty()) {
            val keySentences = mutableListOf<String>()
            for (para in middle) {
                val sentences = splitSentences(para)
                val indices = findKeySentences(sentences)
                indices.forEach { keySentences.add(sentences[it].trim()) }
            }

            if (keySentences.isNotEmpty()) {
                sb.appendLine("--- Key Points ---")
                keySentences.take(MAX_KEY_SENTENCES).forEach { s ->
                    if (s.length > 10) sb.appendLine("• $s")
                }
                sb.appendLine()
            }
        }

        // 末段保留
        sb.append(paragraphs.last())

        return sb.toString()
    }

    private fun splitSentences(text: String): List<String> {
        return text.split(Regex("(?<=[.!?。！？])\\s+"))
            .map { it.trim() }
            .filter { it.length > 5 }
    }

    private fun findKeySentences(sentences: List<String>): Set<Int> {
        val keySet = mutableSetOf<Int>()
        var quota = MAX_KEY_SENTENCES

        // 包含关键模式的句子
        sentences.forEachIndexed { i, s ->
            if (quota <= 0) return@forEachIndexed
            if (keySentencePattern.containsMatchIn(s) && i !in keySet) {
                keySet.add(i)
                quota--
            }
        }

        // 如果关键句不够, 均匀采样
        if (keySet.size < 3 && sentences.size > 5) {
            val step = sentences.size / 3
            for (j in 1..2) {
                val idx = j * step
                if (idx in sentences.indices && idx !in keySet) {
                    keySet.add(idx)
                }
            }
        }

        return keySet
    }

    private fun buildCompressed(
        sentences: List<String>,
        keyIndices: Set<Int>,
        originalLength: Int
    ): String {
        val sb = StringBuilder()
        sb.appendLine(sentences.first())
        sb.appendLine()

        val sorted = keyIndices.sorted()
        if (sorted.isNotEmpty()) {
            sb.appendLine("--- Key Points ---")
            sorted.forEach { i ->
                sb.appendLine("• ${sentences[i].trim()}")
            }
            sb.appendLine()
        }

        sb.append(sentences.last())

        return sb.toString()
    }

    fun originalSize(text: String) = text.length
    fun compressedSize(text: String) = compress(text)?.length ?: 0
}
