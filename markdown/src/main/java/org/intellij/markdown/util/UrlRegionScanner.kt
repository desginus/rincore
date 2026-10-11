package org.intellij.markdown.util

/**
 * v4.8.122 (用户实证: 图片地址里一个 '_' 就足以让图挂 — 只剩 alt):
 * URL/地址域扫描器 — 「地址是不可透明的 URL 令牌」的判定件。
 *
 * 背景: app 层 preProcess 的「词内下划线保护」(v4.8.17, '_'→全角＿) 与
 * 「单波浪号替换」(v4.5.11, '~'→∼) 此前只跳过代码块 — 会把 markdown
 * 图片/链接目标区、autolink 与裸 scheme:// URL 里的 '_'/'~' 一并换掉,
 * 地址被污染 → resolver/Coil 找不到真实文件 (真实文件名仍是 ASCII) →
 * 加载失败只剩 alt (本地 kotlinc 探针实锤: shot_y5_1.png → shot＿y5＿1.png)。
 *
 * 本件覆盖四类地址域 (保守: 宁可多保护, 不可漏保护):
 *   ① markdown 内联目标 ]( ... )  (支持 <...> 与平衡括号)
 *   ② autolink <scheme:...>
 *   ③ 裸 scheme:// URL (到空白 / CJK / 全角 / 引号 / 尖括号停止)
 *   ④ 参考式定义 [id]: url
 * 边界说明: 裸绝对路径 (/workspace/...) 暂不纳入 (正文误伤面大于收益,
 * 记入文档; 模型输出的图片/链接以 ①③ 形态为主)。
 */
object UrlRegionScanner {

    // 裸 scheme:// URL — 边界=空白 / CJK / 全角标点 / 全角区 / <>"'`
    private val BARE_URL = Regex("[a-zA-Z][a-zA-Z0-9+.\\-]{1,31}://[^\\s\\u3000-\\u303f\\u4e00-\\u9fff\\uff00-\\uffef<>\"'`]+")

    // autolink <scheme:...>
    private val AUTOLINK = Regex("<([a-zA-Z][a-zA-Z0-9+.\\-]{1,31}:[^<>\\s]*)>")

    // 参考式定义行 [id]: <target> | [id]: target
    private val REF_DEF = Regex("(?m)^ {0,3}\\[[^\\]\\n]*\\]:\\s*(?:<([^>\\s]*)>|(\\S+))")

    /** 返回需要"防文本变换"的地址域 (已排序合并)。 */
    fun findProtectedRegions(text: String): List<IntRange> {
        val out = ArrayList<IntRange>()

        // ① markdown 内联目标: ]( ... )  (支持 <...> 与平衡括号)
        var i = 0
        while (true) {
            val idx = text.indexOf("](", i)
            if (idx < 0) break
            var j = idx + 2
            while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
            if (j < text.length && text[j] == '<') {
                val close = text.indexOf('>', j + 1)
                if (close > j + 1 && text.indexOf(')', close) >= 0) {
                    out.add((j + 1) until close)
                    i = close + 1
                    continue
                }
            }
            var depth = 0
            var k = j
            var end = -1
            while (k < text.length) {
                val c = text[k]
                when {
                    c == '\\' -> k += 2
                    c == '(' -> {
                        depth++; k++
                    }

                    c == ')' -> {
                        if (depth == 0) {
                            end = k; break
                        } else {
                            depth--; k++
                        }
                    }

                    c == ' ' || c == '\t' || c == '\n' || c == '\r' -> break
                    else -> k++
                }
            }
            if (end > j) out.add(j until end)
            i = idx + 2
        }

        // ② autolink <scheme:...>
        AUTOLINK.findAll(text).forEach { m -> m.groups[1]?.range?.let { out.add(it) } }

        // ③ 裸 scheme:// URL
        BARE_URL.findAll(text).forEach { out.add(it.range) }

        // ④ 参考式定义 [id]: url
        REF_DEF.findAll(text).forEach { m ->
            (m.groups[1] ?: m.groups[2])?.range?.let { out.add(it) }
        }

        if (out.isEmpty()) return emptyList()
        val sorted = out.sortedBy { it.first }
        val merged = ArrayList<IntRange>()
        var cur: IntRange? = null
        for (r in sorted) {
            val c = cur
            if (c == null) {
                cur = r
            } else if (r.first <= c.last + 1) {
                cur = c.first..maxOf(c.last, r.last)
            } else {
                merged.add(c); cur = r
            }
        }
        cur?.let { merged.add(it) }
        return merged
    }
}
