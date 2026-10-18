/* 【域 D·渲染链】 | 地图: docs/APP_MAP.md §D */
package me.rerere.rikkahub.ui.components.richtext

import org.intellij.markdown.html.HtmlGenerator
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node

/**
 * 流式正文渲染引擎 —— 两条渲染路径共用的增量底座（v4.8.86 重构）。
 *
 * ## 为什么要有这个文件
 * 渲染链此前是"两套宇宙"：
 *  · AST 块路径（Markdown.kt）—— 有块级缓存 + 增量切分状态机；
 *  · HTML 文档路径（MarkdownNew.kt）—— **每次内容变化整段重解析**（preProcess 8 趟正则 +
 *    全量 AST + HtmlGenerator + Jsoup.parse），并且它的 preProcess/parser/flavour 是
 *    Markdown.kt 的**副本**（还漂移过：副本内部甚至没用自己声明的预编译正则）。
 * 结果：只要正文里出现任意 HTML 标签，长消息每写一个字就付一次 O(全文) 的解析成本，
 * 外加主线程整棵 DOM 重建 —— 表现就是"上下文长了以后输出忽然变慢"。
 *
 * ## 本文件收敛的三件事（唯一实现）
 *  ① [MarkdownFence] + [BlockScanner] —— 块边界规则（围栏内不切分、空行为界）与增量扫描（只扫新增行）。
 *  ② [TextBudgetLru] —— 缓存按**字符预算**淘汰（旧实现按条数：256×128K + 2048×64K 理论上百兆级）。
 *  ③ [HtmlStreamRenderer] —— HTML 路径的**增量文档**：定稿块解析一次后常驻容器，每 tick 只解析尾块。
 *
 * ## 增量铁律
 * 两条路径每 tick 成本都必须 = O(新增 + 尾块)，与正文总长解耦。
 * 定稿块一律沿用同一批节点实例（Compose strong skipping 据此跳过重组）。
 *
 * ## 节点所有权
 * 缓存里存的是**母本**，渲染侧一律用 `clone()` —— Jsoup 节点只有一个父节点，
 * 直接复用母本会被 append 搬走，造成另一处渲染错乱。克隆后由渲染实例独占。
 */

/** 围栏判定 —— 块边界规则的唯一定义处（原 Markdown.kt/MarkdownNew.kt 各有一份）。 */
internal object MarkdownFence {
    /** 行首围栏标记；非围栏行返回 null。```/~~~ 同类配对，$$、\[、\] 各自成对。 */
    fun of(trimmedLine: String): Char? = when {
        trimmedLine.startsWith("```") -> '`'
        trimmedLine.startsWith("~~~") -> '~'
        trimmedLine == "$$" -> '$'
        trimmedLine == "\\[" -> '['
        trimmedLine == "\\]" -> ']'
        else -> null
    }

    /** 围栏状态推进 —— 返回 (是否在围栏内, 当前围栏字符)。 */
    fun next(open: Boolean, current: Char, delim: Char): Pair<Boolean, Char> = when (delim) {
        '[' -> if (!open) true to '[' else open to current
        ']' -> if (open && current == '[') false to ' ' else open to current
        else -> when {
            !open -> true to delim
            current == delim -> false to ' '
            else -> open to current
        }
    }
}

/**
 * 增量块扫描器 —— 只扫"上次之后新增的完整行"，产出**新定稿块**的区间。
 *
 * 契约：
 *  · 空行（围栏外）= 块边界；未闭合围栏内的空行不算边界；
 *  · 末尾不完整行不消费（留待下次）；
 *  · 内容非追加（编辑/重新生成）时自动从头重扫；
 *  · `blockStart` 恒为"当前未定稿尾块"的起点。
 */
internal class BlockScanner {
    private var consumed = 0
    private var start = 0
    private var fenceOpen = false
    private var fenceChar = ' '
    private var prev: String? = null

    /** 当前尾块起点（content 偏移）。 */
    val blockStart: Int get() = start

    fun reset() {
        consumed = 0
        start = 0
        fenceOpen = false
        fenceChar = ' '
        prev = null
    }

    fun advance(content: String): List<IntRange> {
        val p = prev
        if (p == null || content.length < p.length || !content.startsWith(p)) {
            consumed = 0
            start = 0
            fenceOpen = false
            fenceChar = ' '
        }
        prev = content
        val out = ArrayList<IntRange>()
        var pos = consumed
        val len = content.length
        while (pos < len) {
            val nl = content.indexOf('\n', pos)
            if (nl < 0) break // 末尾不完整行：留待下次
            val lineStart = pos
            pos = nl + 1
            val trimmed = content.substring(lineStart, nl).trim()
            val delim = MarkdownFence.of(trimmed)
            if (delim != null) {
                val (o, c) = MarkdownFence.next(fenceOpen, fenceChar, delim)
                fenceOpen = o
                fenceChar = c
            }
            if (!fenceOpen && trimmed.isEmpty()) {
                if (lineStart > start) out.add(start until lineStart)
                start = pos
            }
        }
        consumed = pos
        return out
    }
}

/**
 * 按字符预算淘汰的 LRU（键为正文串，键长即内存占用的主项）。
 * 单条超过预算一半直接不缓存（避免一条巨串挤掉全部）。
 */
internal class TextBudgetLru<V>(private val maxChars: Int) {
    private val map = LinkedHashMap<String, V>(64, 0.75f, true)
    private var used = 0

    @Synchronized
    fun get(key: String): V? = map[key]

    @Synchronized
    fun put(key: String, value: V) {
        if (key.length > maxChars / 2) return
        if (map.put(key, value) == null) used += key.length
        while (used > maxChars) {
            val it = map.entries.iterator()
            if (!it.hasNext()) break
            used -= it.next().key.length
            it.remove()
        }
    }
}

/** markdown → HTML 串（HTML 渲染路径的块级入口；preProcess/parser 复用 Markdown.kt 的唯一实现）。 */
internal fun markdownToHtml(content: String): String {
    val pre = preProcess(content)
    val tree = parser.buildMarkdownTreeFromString(pre)
    return HtmlGenerator(pre, tree, flavour).generateHtml()
}

/** 一段 markdown 文本 → Jsoup 顶层节点（快照列表，调用方可安全搬移）。 */
private fun htmlNodesOfBlock(block: String): List<Node> {
    val html = runCatching { markdownToHtml(block) }.getOrElse { "" }
    val body = runCatching { Jsoup.parseBodyFragment(html).body() }.getOrNull() ?: return emptyList()
    return ArrayList(body.childNodes())
}

/** 定稿块的节点母本缓存 —— 只收定稿块（内容稳定，键不抖动），流式尾块从不写缓存。 */
private object HtmlBlockMasters {
    private val cache = TextBudgetLru<List<Node>>(1_500_000)

    fun nodesOf(block: String): List<Node> =
        cache.get(block) ?: htmlNodesOfBlock(block).also { cache.put(block, it) }
}

/**
 * HTML 路径的流式渲染件（每个 MarkdownNew 实例持有一个，与组合生命周期一致）。
 *
 * 每 tick：
 *  ① 吃掉新定稿块 → 母本克隆后 append 进常驻容器（此后再不重解析）；
 *  ② 摘掉上一 tick 的尾块节点，只重解析当前尾块；
 *  ③ 返回容器子节点快照 —— 定稿块节点实例跨 tick 稳定 → Compose 跳过其重组。
 */
internal class HtmlStreamRenderer {
    private val scanner = BlockScanner()
    private val body: Element = Jsoup.parseBodyFragment("").body()
    private val tailNodes = ArrayList<Node>()
    private var prev: String? = null

    @Synchronized
    fun nodes(content: String): List<Node> {
        val p = prev
        if (p == null || content.length < p.length || !content.startsWith(p)) {
            scanner.reset()
            body.empty()
            tailNodes.clear()
        }

        // ① 新定稿块 —— 解析一次，常驻
        for (range in scanner.advance(content)) {
            val block = content.substring(range.first, range.last + 1).trimEnd('\r', '\n')
            if (block.isEmpty()) continue
            for (master in HtmlBlockMasters.nodesOf(block)) body.appendChild(master.clone())
        }

        // ② 尾块 —— 现场解析（不写缓存：流式中间态会污染缓存）
        for (n in tailNodes) n.remove()
        tailNodes.clear()
        val tailStart = scanner.blockStart
        if (tailStart < content.length) {
            val tailText = content.substring(tailStart).trimEnd('\r', '\n')
            if (tailText.isNotEmpty()) {
                for (n in htmlNodesOfBlock(tailText)) {
                    body.appendChild(n) // appendChild 自动从片段 body 摘除
                    tailNodes.add(n)
                }
            }
        }

        prev = content
        return ArrayList(body.childNodes())
    }
}
