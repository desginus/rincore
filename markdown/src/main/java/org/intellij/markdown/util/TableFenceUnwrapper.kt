package org.intellij.markdown.util

/**
 * v4.8.120 (codex 对齐改造): ```md / ```markdown 围栏内「表格」解包。
 *
 * 背景 (与 openai/codex 同题实锤): LLM 常把表格包进 ```markdown / ```md 围栏当
 * 代码输出 — 不移除围栏, 整张表会以代码块渲染 (表格结构/单元格公式/强调全灭)。
 * 本件为 openai/codex `codex-rs/tui/src/markdown.rs::unwrap_markdown_fences`
 * (+ table_detect.rs 的判定件) 的 Kotlin 移植, 保守语义逐条保持:
 *   ① 仅解包 info string 为 md/markdown (大小写不敏感) 的围栏;
 *   ② 缓冲整个围栏体后才判定 — 仅当体内存在「表头行 + 紧邻分隔行」才解包;
 *   ③ 不含表格的 markdown 围栏 / 其他语言围栏原样保留;
 *   ④ 未闭合围栏在原位原样回收 (流式半截围栏降级为代码显示, 不破坏增量渲染)。
 *
 * 调用点: app 侧 preProcess 首步 (进行任何代码块判定/公式转换之前)。
 */
object TableFenceUnwrapper {

    fun unwrap(source: String): String {
        // 零拷贝快路径: 绝大多数消息不含围栏
        if (!source.contains("```") && !source.contains("~~~")) return source

        val lines = source.split("\n")
        val out = StringBuilder(source.length)
        var outEmpty = true
        fun emit(line: String) {
            if (!outEmpty) out.append('\n')
            out.append(line)
            outEmpty = false
        }

        var inFence = false
        var fenceMarker = ' '
        var fenceLen = 0
        var fenceIsMarkdown = false
        var fenceBlockquoted = false
        var openingLine: String? = null
        val body = ArrayList<String>()

        for (line in lines) {
            if (inFence) {
                if (isCloseFence(line, fenceMarker, fenceLen, fenceBlockquoted)) {
                    val containsTable = fenceIsMarkdown &&
                        markdownFenceContainsTable(body, fenceBlockquoted)
                    if (fenceIsMarkdown && containsTable) {
                        // 解包: 只发射体内容, 丢弃围栏行
                        body.forEach { emit(it) }
                    } else {
                        emit(openingLine!!)
                        body.forEach { emit(it) }
                        emit(line)
                    }
                    inFence = false
                    body.clear()
                    openingLine = null
                } else {
                    body.add(line)
                }
                continue
            }

            val open = parseOpenFence(line)
            if (open != null) {
                inFence = true
                fenceMarker = open.marker
                fenceLen = open.len
                fenceIsMarkdown = open.isMarkdown
                fenceBlockquoted = open.blockquoted
                openingLine = line
                body.clear()
            } else {
                emit(line)
            }
        }

        if (inFence) {
            // 未闭合围栏: 原样回收 — 流式半截围栏降级为代码显示
            emit(openingLine!!)
            body.forEach { emit(it) }
        }
        return out.toString()
    }

    private class OpenFence(
        val marker: Char,
        val len: Int,
        val isMarkdown: Boolean,
        val blockquoted: Boolean,
    )

    private fun parseOpenFence(line: String): OpenFence? {
        val trimmed = stripLineIndent(line) ?: return null
        val blockquoted = trimmed.trimStart().startsWith(">")
        val scanText = if (blockquoted) stripBlockquotePrefix(trimmed) else trimmed
        val marker = parseFenceMarker(scanText) ?: return null
        val isMarkdown = isMarkdownFenceInfo(scanText, marker.second)
        return OpenFence(marker.first, marker.second, isMarkdown, blockquoted)
    }

    private fun isCloseFence(line: String, marker: Char, len: Int, blockquoted: Boolean): Boolean {
        val trimmed = stripLineIndent(line) ?: return false
        val scanText = if (blockquoted) {
            if (!trimmed.trimStart().startsWith(">")) return false
            stripBlockquotePrefix(trimmed)
        } else {
            trimmed
        }
        val parsed = parseFenceMarker(scanText) ?: return false
        return parsed.first == marker && parsed.second >= len &&
            scanText.substring(parsed.second).trim().isEmpty()
    }

    private fun markdownFenceContainsTable(body: List<String>, blockquoted: Boolean): Boolean {
        var previous: String? = null
        for (raw in body) {
            val text = if (blockquoted) stripBlockquotePrefix(raw) else raw
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                previous = null
                continue
            }
            val prev = previous
            if (prev != null && isTableHeaderLine(prev) && !isTableDelimiterLine(prev) &&
                isTableDelimiterLine(trimmed)
            ) {
                return true
            }
            previous = trimmed
        }
        return false
    }

    // ---- table_detect.rs 移植 (结构判定, 非渲染) ----

    /** 剥 0-3 个前导空格; 4+ 缩进 (CommonMark 缩进代码) 返回 null。 */
    private fun stripLineIndent(line: String): String? {
        var idx = 0
        var column = 0
        while (idx < line.length) {
            when (line[idx]) {
                ' ' -> {
                    idx++; column++
                }

                '\t' -> {
                    idx++; column += 4
                }

                else -> break
            }
            if (column >= 4) return null
        }
        return line.substring(idx)
    }

    /** 行首围栏标记 (至少 3 个连续 ` 或 ~); 非围栏行返回 null。 */
    private fun parseFenceMarker(line: String): Pair<Char, Int>? {
        val first = line.firstOrNull() ?: return null
        if (first != '`' && first != '~') return null
        var len = 0
        while (len < line.length && line[len] == first) len++
        if (len < 3) return null
        return first to len
    }

    /** info string 首个词为 md / markdown (大小写不敏感)。 */
    private fun isMarkdownFenceInfo(line: String, markerLen: Int): Boolean {
        if (markerLen > line.length) return false
        val info = line.substring(markerLen).trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        return info.equals("md", ignoreCase = true) || info.equals("markdown", ignoreCase = true)
    }

    /** 剥掉所有前导 `>` 块引用标记。 */
    private fun stripBlockquotePrefix(line: String): String {
        var rest = line.trimStart()
        while (true) {
            if (!rest.startsWith(">")) return rest
            rest = rest.substring(1)
            rest = if (rest.startsWith(" ")) rest.substring(1).trimStart() else rest.trimStart()
        }
    }

    /** 按未转义 `|` 切段 (结构判定; `\|` 视为字面文本, 反斜杠保留)。 */
    private fun parseTableSegments(line: String): List<String>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val hasOuterPipe = trimmed.startsWith("|") || trimmed.endsWith("|")
        var content = trimmed
        if (content.startsWith("|")) content = content.substring(1)
        if (content.endsWith("|")) content = content.dropLast(1)
        val segments = splitUnescapedPipe(content).map { it.trim() }
        if (!hasOuterPipe && segments.size <= 1) return null
        return if (segments.isEmpty()) null else segments
    }

    private fun splitUnescapedPipe(content: String): List<String> {
        val segments = ArrayList<String>(8)
        var start = 0
        var i = 0
        while (i < content.length) {
            when (content[i]) {
                '\\' -> i += 2
                '|' -> {
                    segments.add(content.substring(start, i))
                    start = i + 1
                    i++
                }

                else -> i++
            }
        }
        segments.add(content.substring(start))
        return segments
    }

    /** 表头行: 含 `|` 分段且至少一个非空段。 */
    private fun isTableHeaderLine(line: String): Boolean =
        parseTableSegments(line)?.any { it.isNotEmpty() } == true

    /** 分隔段: `-` / `:-` / `-:` / `:-:` (连字符数量与解析器口径对齐)。
     *
     * 注: codex 原版要求 ≥3 连字符 (其 pulldown-cmark 栈的保守 holdback 判据);
     * 我们的 GFM 解析器接受 ≥1 (实测 1/2/3 连字符均识别为表格) — 阈值贴合本仓
     * 解析器, 避免"解析器认表、解包器不认 → 表格仍被围栏包着渲染成代码"的错位。
     */
    private fun isTableDelimiterSegment(segment: String): Boolean {
        val trimmed = segment.trim()
        if (trimmed.isEmpty()) return false
        var core = trimmed
        if (core.startsWith(":")) core = core.substring(1)
        if (core.endsWith(":")) core = core.dropLast(1)
        return core.isNotEmpty() && core.all { it == '-' }
    }

    /** 分隔行: 所有段都是合法分隔段。 */
    private fun isTableDelimiterLine(line: String): Boolean =
        parseTableSegments(line)?.let { segs ->
            segs.isNotEmpty() && segs.all { isTableDelimiterSegment(it) }
        } == true
}
