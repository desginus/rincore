/* 【域 F·主题渲染】 — 消息/文档渲染 | 地图: docs/APP_MAP.md §F */
package me.rerere.rikkahub.ui.components.richtext


/* ───【原版对齐】Markdown.kt | 差异 ±22 行
 * 来源: 原版移植 + 自研小调整 (未达专项标注阈值, 对齐细节见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.table.DataTable
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.modifier.onClick
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.toDp
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.LeafASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import kotlin.time.Clock

internal val flavour by lazy {
    GFMFlavourDescriptor(
        makeHttpsAutoLinks = true, useSafeLinks = true
    )
}

internal val parser by lazy {
    MarkdownParser(flavour)
}

private val INLINE_LATEX_REGEX = Regex("\\\\\\((.+?)\\\\\\)")
private val BLOCK_LATEX_REGEX = Regex("\\\\\\[(.+?)\\\\\\]", RegexOption.DOT_MATCHES_ALL)
val THINKING_REGEX = Regex("<think>([\\s\\S]*?)(?:</think>|$)", RegexOption.DOT_MATCHES_ALL)
private val CODE_BLOCK_REGEX = Regex("```[\\s\\S]*?```|`[^`\n]*`", RegexOption.DOT_MATCHES_ALL)
private val BREAK_LINE_REGEX = Regex("(?i)<br\\s*/?>")
private val LATEX_BLOCK_LINE_BREAK_REGEX = Regex("""[ \t]*\r?\n[ \t]*""")
// v4.8.1: 热路径正则预编译 — 原为 preProcess 内每次调用现场 Regex() 构造 (JVM 每次
// 编译 pattern), 进入对话首帧集中解析时是主要 CPU 开销之一; 提为顶层常量仅编译一次。
private val MATH_SEG_REGEX = Regex("\\$\\$[\\s\\S]*?\\$\\$|\\$[^$\\n]*\\$")
private val SINGLE_TILDE_REGEX = Regex("(?<!~)~(?!~)")
private val MATH_RESTORE_REGEX = Regex("\\u0000MATH(\\d+)\\u0000")

// v4.8.17: 词内下划线保护 — 用户实证 M_O / xF_y 类文本渲染异常 (下划线被
// 强调解析破坏, 而 yF_x 等孤立下划线反而正常)。修复: 字母/数字之间的 `_`
// 替换为全角下划线 ＿ (U+FF3F) — 视觉等效、不参与 ASCII 强调定界解析、
// 复制仍可见。`_em_` 类正常强调 (下划线外侧为空白/标点) 不受影响。
private val INTRAWORD_UNDERSCORE_REGEX = Regex("(?<=[A-Za-z0-9])_(?=[A-Za-z0-9])")

// v4.8.86: preProcess / flavour / parser 提升为 internal —— 它们是**两条渲染路径的唯一实现**
// (MarkdownNew.kt 里那份副本已删除; 副本此前还漂移过: 内部没用自己声明的预编译正则)。
internal fun preProcess(content: String): String {
    // 先找出所有代码块的位置
    val codeBlocks = mutableListOf<IntRange>()
    CODE_BLOCK_REGEX.findAll(content).forEach { match ->
        codeBlocks.add(match.range)
    }

    // 检查位置是否在代码块内
    fun isInCodeBlock(position: Int): Boolean {
        return codeBlocks.any { range -> position in range }
    }

    // 替换行内公式 \( ... \) 到 $ ... $，但跳过代码块内的内容
    var result = INLINE_LATEX_REGEX.replace(content) { matchResult ->
        if (isInCodeBlock(matchResult.range.first)) {
            matchResult.value // 保持原样
        } else {
            "$" + matchResult.groupValues[1] + "$"
        }
    }

    // 替换块级公式 \[ ... \] 到 $$ ... $$，但跳过代码块内的内容
    result = BLOCK_LATEX_REGEX.replace(result) { matchResult ->
        if (isInCodeBlock(matchResult.range.first)) {
            matchResult.value // 保持原样
        } else {
            val formula = matchResult.groupValues[1]
                .trim()
                .replace(LATEX_BLOCK_LINE_BREAK_REGEX, " ")
            "$$" + formula + "$$"
        }
    }

    // v4.5.11: 单波浪号改用字符替换而非转义 — ①fork 解析器对 \~ 转义支持
    // 不可靠, 转义后仍可能被当删除线渲染 (贯穿线); ②公式内 ~ 会被破坏
    // (\~ 是 LaTeX 重音符), jlatexmath 失败后公式回退纯文本显示。
    // 替换为 Unicode 近似号 ∼ (U+223C): 不触发删除线; 文本语境 (~30%)
    // 显示波浪号本身; ~~text~~ 双波浪删除线保留; 代码块内原样。
    // v4.5.14: 公式段区分 — 公式语境统一转 LaTeX 标准命令 \sim
    // (jlatexmath 对 Unicode ∼ 的支持未验证, \sim 是核心符号必支持),
    // 文本语境保持 ∼ 字符; 代码块内 $ 段不占位 (isInCodeBlock 原样)。
    val mathSegs = mutableListOf<String>()
    val withMathPlaceholder = MATH_SEG_REGEX.replace(result) { m ->
        if (isInCodeBlock(m.range.first)) m.value
        else {
            mathSegs.add(m.value)
            "\u0000MATH${mathSegs.size - 1}\u0000"
        }
    }
    val textDone = SINGLE_TILDE_REGEX.replace(withMathPlaceholder) { m ->
        if (isInCodeBlock(m.range.first)) m.value else "\u223C"
    }
    // v4.8.17: 词内下划线 → 全角下划线 (公式段已占位, 不受影响; 代码块内原样)
    val underscoreDone = INTRAWORD_UNDERSCORE_REGEX.replace(textDone) { m ->
        if (isInCodeBlock(m.range.first)) m.value else "＿"
    }
    result = MATH_RESTORE_REGEX.replace(underscoreDone) { m ->
        mathSegs[m.groupValues[1].toInt()].replace(SINGLE_TILDE_REGEX, "\\sim")
    }

    return result
}

@Preview(showBackground = true)
@Composable
private fun MarkdownPreview() {
    MaterialTheme {
        CompositionLocalProvider(LocalSettings provides Settings()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                MarkdownBlock(
                    content = "Hi there!", modifier = Modifier.background(Color.Red)
                )
                MarkdownBlock(
                    content = """
                    ### 🌍 This is Markdown Test This Markdown Test
                    1. How many roads must a man walk down
                        * the slings and arrows of outrageous fortune, Or to take arms against a sea of troubles,
                        * by opposing end them.
                            * How many times must a man look up, Before he can see the sky?
                            * How many times $ f(x) = \sum_{n=0}^{\infty} \frac{f^{(n)}(a)}{n!}(x-a)^n$
                    2. How many times must a man look up, Before he can see the sky?

                    * [ ] Before they're allowed to be free? Yes, 'n' how many times can a man turn his head
                    * [x] Before they're allowed to be free? Yes, 'n' how many times can a man turn his head

                    4. For in that sleep of death what dreams may come [citation](1)

                    This is Markdown Test, This <br/> is Markdown Test.
                    ha<br/>ha

                    ***
                    This is Markdown Test, This is Markdown Test.

                    | Name | Age | Address | Email | Job | Homepage |
                    | ---- | --- | ------- | ----- | --- | -------- |
                    | John | 25  | New York | john@example.com | Software Engineer | john.com |
                    | Jane | 26  | London   | jane@example.com | Data Scientist | jane.com |

                    ## HTML Escaping
                    This is a &gt;  test
                """.trimIndent()
                )
            }
        }
    }
}

/** v4.8.68: 渲染源块 — 顶层 AST 节点 + 其所属内容串 (节点偏移与内容串自洽)。 */
internal class MarkdownNodeSource(val node: ASTNode, val content: String)

/**
 * v4.8.68 (CS 安卓端速度方案落地): 解析结果 = 顶层渲染块列表。
 * 稳定块跨 tick 复用同一 AST 实例与同一内容串 (块级缓存命中),
 * Compose strong skipping 据此跳过稳定块重组 — 每 tick 只有尾部块
 * 付出解析/渲染成本 (对齐 CS streamdown "只为变化的尾部工作")。
 * 渲染树仍为整段单一结构 — 视觉与 v4.7.26 定版形态完全一致。
 */
internal data class MarkdownParseResult(
    val blocks: List<MarkdownNodeSource>,
    val hasHtml: Boolean,
)

/**
 * v4.8.49 回植 (原 v4.8.1/42): 解析结果 LRU 缓存 — 命中时首帧同步即终态
 * (零解析零过渡); 未命中时首帧同步解析 (对齐原版形态) 并写缓存。容量 256
 * (v4.8.42 状态); 预热机制不再恢复 — 缓存独立生效, 无进入时后台风暴。
 */
/**
 * v4.8.86: 增量切分状态 (per MarkdownBlock 实例持有, remember 生命周期)。
 * 与 HTML 路径**共用** [BlockScanner]（块边界规则的唯一定义处）。
 * prefix = 超过块数上限时零解析并入的早期块（旧实现在此处回退全量解析 → 长消息每 tick 全量重解析）。
 */
internal class SplitState {
    val scanner = BlockScanner()
    val finalized = ArrayList<MarkdownParseResult>()
    val prefix = ArrayList<MarkdownParseResult>()
}

private object MarkdownParseCache {
    // v4.8.86: 缓存改为**字符预算**淘汰。
    // 旧实现按条数 (整段 256 条 / 块 2048 条) 且单键上限 128K/64K —— 理论上可堆积
    // 数十兆的 AST 与预处理串, 长会话下是 GC 抖动的来源。预算按"键长"计, 因为
    // AST 结果的内存主项就是它引用的那份预处理串; 单条超预算一半直接不收。
    private const val WHOLE_BUDGET_CHARS = 1_000_000
    private const val BLOCK_BUDGET_CHARS = 1_500_000
    private const val MAX_SINGLE_KEY_CHARS = 200_000
    private const val MAX_SPLIT_BLOCKS = 512

    private val wholeCache = TextBudgetLru<MarkdownParseResult>(WHOLE_BUDGET_CHARS)
    private val blockCache = TextBudgetLru<MarkdownParseResult>(BLOCK_BUDGET_CHARS)

    internal fun getWhole(key: String): MarkdownParseResult? = wholeCache.get(key)
    internal fun putWhole(key: String, value: MarkdownParseResult) = wholeCache.put(key, value)
    internal fun getBlock(key: String): MarkdownParseResult? = blockCache.get(key)
    internal fun putBlock(key: String, value: MarkdownParseResult) = blockCache.put(key, value)

    internal fun newState(): SplitState = SplitState()

    /** 首帧/预热: 整段命中零解析; 未命中走块级管线 (全部块写回)。 */
    internal fun parseWithCache(content: String): MarkdownParseResult =
        getWhole(content) ?: parseViaBlocks(content, writeBackAll = true).also { putWhole(content, it) }

    /**
     * v4.8.86: 增量解析（流式 tick 专用）—— 扫描器只扫新增完整行 (O(δ)):
     *  · 已定稿块直接携带缓存结果（零重扫零重查, 节点实例跨 tick 复用 → strong skipping 跳过重组）;
     *  · 仅未定稿尾块现场解析（不写回, 不污染缓存）;
     *  · **块数超上限不再回退全量解析** —— 旧实现在此处 reset + parseFull(全文), 于是
     *    长消息一旦越过阈值就变成"每一分片全量重解析"(preProcess 8 趟正则 + 全量 AST),
     *    这正是"上下文长了以后输出忽然变慢"的主因; 现改为把最早的若干块**零解析并入前缀槽**。
     * 内容非追加 (编辑/重新生成) 时扫描器自动从头重扫。
     */
    internal fun parseTransient(content: String, state: SplitState): MarkdownParseResult {
        for (range in state.scanner.advance(content)) {
            val blockText = content.substring(range.first, range.last + 1).trimEnd('\r', '\n')
            if (blockText.isEmpty()) continue
            state.finalized.add(getBlock(blockText) ?: parseFull(blockText).also { putBlock(blockText, it) })
        }
        if (state.finalized.size > MAX_SPLIT_BLOCKS) {
            val mergeCount = state.finalized.size - MAX_SPLIT_BLOCKS / 2
            state.prefix.addAll(state.finalized.subList(0, mergeCount))
            state.finalized.subList(0, mergeCount).clear()
        }
        val out = ArrayList<MarkdownNodeSource>(state.prefix.size + state.finalized.size * 2 + 2)
        var hasHtml = false
        for (r in state.prefix) {
            out.addAll(r.blocks)
            hasHtml = hasHtml || r.hasHtml
        }
        for (r in state.finalized) {
            out.addAll(r.blocks)
            hasHtml = hasHtml || r.hasHtml
        }
        val tailStart = state.scanner.blockStart
        if (tailStart < content.length) {
            val tailBlock = content.substring(tailStart).trimEnd('\r', '\n')
            if (tailBlock.isNotEmpty()) {
                val r = parseFull(tailBlock)
                out.addAll(r.blocks)
                hasHtml = hasHtml || r.hasHtml
            }
        }
        if (out.isEmpty()) return parseFull(content)
        return MarkdownParseResult(out, hasHtml)
    }


    /** v4.8.50: 未命中才解析 (预热专用; 命中零成本跳过)。 */
    fun warmIfAbsent(content: String) {
        if (content.length > MAX_SINGLE_KEY_CHARS) return
        if (getWhole(content) != null) return
        putWhole(content, parseViaBlocks(content, writeBackAll = true))
    }

    /**
     * v4.8.68: 块级解析管线 — 按顶层块切分后逐块取缓存:
     * 稳定块命中 (零解析, 复用同实例), 仅尾块现场解析;
     * 输出 = 各块顶层节点平铺 (节点与各自内容串偏移自洽, 无需位移)。
     * 单块 / 超块数 → 整段解析 (与旧路径等价)。
     */
    private fun parseViaBlocks(content: String, writeBackAll: Boolean): MarkdownParseResult {
        val parts = splitMarkdownBlocks(content)
        if (parts.size <= 1 || parts.size > MAX_SPLIT_BLOCKS) {
            return parseFull(content)
        }
        val out = ArrayList<MarkdownNodeSource>(parts.size * 2)
        var hasHtml = false
        for ((index, block) in parts.withIndex()) {
            val isLast = index == parts.lastIndex
            val blockResult = getBlock(block) ?: run {
                val parsed = parseFull(block)
                if (writeBackAll || !isLast) putBlock(block, parsed)
                parsed
            }
            out.addAll(blockResult.blocks)
            hasHtml = hasHtml || blockResult.hasHtml
        }
        return MarkdownParseResult(out, hasHtml)
    }
}

/**
 * v4.8.86: 按顶层块边界切分 —— 边界规则与增量扫描**共用** [BlockScanner]（唯一定义处）。
 * 旧实现自带一份围栏判定（与扫描器那份重复），加上 MarkdownNew 的副本，同一规则曾有三份。
 * 流式追加时已完成块内容串逐字稳定 → 块级缓存恒定命中。
 */
private fun splitMarkdownBlocks(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val scanner = BlockScanner()
    val ranges = scanner.advance(text)
    val blocks = ArrayList<String>(ranges.size + 1)
    for (r in ranges) {
        val b = text.substring(r.first, r.last + 1).trimEnd('\r', '\n')
        if (b.isNotEmpty()) blocks.add(b)
    }
    if (scanner.blockStart < text.length) {
        val tail = text.substring(scanner.blockStart).trimEnd('\r', '\n')
        if (tail.isNotEmpty()) blocks.add(tail)
    }
    return blocks
}

/**
 * v4.8.50: 预热入口 (WarmPipeline 调用) — 只填充缓存, 已缓存则零成本跳过;
 * 由调用方负责后台线程; 不触碰任何可见状态 (渲染连续性零破坏)。
 */
fun warmMarkdownCache(content: String) {
    if (content.isBlank()) return
    MarkdownParseCache.warmIfAbsent(content)
}

private fun ASTNode.containsHtml(): Boolean {
    if (type == MarkdownElementTypes.HTML_BLOCK || type == MarkdownTokenTypes.HTML_TAG) return true
    return children.any { it.containsHtml() }
}

private fun parseFull(content: String): MarkdownParseResult {
    val preprocessed = preProcess(content)
    val astTree = parser.buildMarkdownTreeFromString(preprocessed)
    // v4.8.68: 顶层节点平铺为渲染源块 (content = 本段预处理串, 偏移自洽)
    val blocks = astTree.children.map { MarkdownNodeSource(it, preprocessed) }
    return MarkdownParseResult(blocks, astTree.containsHtml())
}

// v4.7.26: 渲染流程完全对齐原版 RikkaHub (用户定版) —
// 首帧同步解析 (parseMarkdown 直接作 remember 初值), 首帧即最终 AST,
// 无"纯文本占位 -> 异步替换"过渡。v4.7.23 分段 / v4.7.25 缓存方案整体撤除
// (原版形态无进入对话/滚动历史抽动)。
// v4.8.68: 渲染形态保持不变 (单树/首帧同步/同节点同边距); 解析层块级化 —
// 稳定块跨 tick 复用实例 (块级缓存命中 + strong skipping 跳过重组),
// 每 tick 仅尾部块付出解析/渲染成本 (CS streamdown 同款语义, 无组件级分块)。
@Composable
fun MarkdownBlock(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    onClickCitation: (String) -> Unit = {}
) {
    // v4.8.49 回植: 首帧查缓存 (命中零解析) — 未命中同步解析并写缓存 (对齐原版首帧终态)。
    var (data, setData) = remember { mutableStateOf(MarkdownParseCache.parseWithCache(content)) }
    // v4.8.70: 增量切分状态 — 与本 MarkdownBlock 实例同生命周期
    val splitState = remember { MarkdownParseCache.newState() }

    // 监听内容变化，重新解析AST树
    // 这里在后台线程解析AST树, 防止频繁更新的时候掉帧
    val updatedContent by rememberUpdatedState(content)
    LaunchedEffect(Unit) {
        snapshotFlow { updatedContent }
            .distinctUntilChanged()
            .mapLatest { MarkdownParseCache.parseTransient(it, splitState) }
            .catch { exception -> exception.printStackTrace() }
            .flowOn(Dispatchers.Default)
            .collect { setData(it) }
    }

    if (data.hasHtml) {
        MarkdownNew(
            content = content,
            modifier = modifier,
            style = style,
            onClickCitation = onClickCitation,
        )
    } else {
        ProvideTextStyle(style) {
            Column(
                modifier = modifier.padding(horizontal = 4.dp)
            ) {
                data.blocks.fastForEach { source ->
                    MarkdownNode(
                        node = source.node, content = source.content, onClickCitation = onClickCitation
                    )
                }
            }
        }
    }
}

// for debug
private fun dumpAst(node: ASTNode, text: String, indent: String = "") {
    println("$indent${node.type} ${if (node.children.isEmpty()) node.getTextInNode(text) else ""} | ${node.javaClass.simpleName}")
    node.children.fastForEach {
        dumpAst(it, text, "$indent  ")
    }
}

object HeaderStyle {
    private const val LINE_HEIGHT_RATIO = 1.25f

    fun fromLevel(level: Int, fontSizeRatio: Float): TextStyle {
        val fontSize = when (level) {
            1 -> 24.sp
            2 -> 22.sp
            3 -> 20.sp
            4 -> 18.sp
            5 -> 16.sp
            else -> 14.sp
        } * fontSizeRatio

        return TextStyle(
            fontStyle = FontStyle.Normal,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            lineHeight = fontSize * LINE_HEIGHT_RATIO,
        )
    }

    fun verticalPadding(level: Int) = when (level) {
        1 -> 16.dp
        2 -> 14.dp
        3 -> 12.dp
        4 -> 10.dp
        5 -> 8.dp
        else -> 6.dp
    }

    fun fromMarkdownType(type: IElementType, fontSizeRatio: Float): TextStyle = fromLevel(
        level = when (type) {
            MarkdownElementTypes.ATX_1 -> 1
            MarkdownElementTypes.ATX_2 -> 2
            MarkdownElementTypes.ATX_3 -> 3
            MarkdownElementTypes.ATX_4 -> 4
            MarkdownElementTypes.ATX_5 -> 5
            MarkdownElementTypes.ATX_6 -> 6
            else -> 6
        },
        fontSizeRatio = fontSizeRatio,
    )

    fun verticalPadding(type: IElementType) = verticalPadding(
        level = when (type) {
            MarkdownElementTypes.ATX_1 -> 1
            MarkdownElementTypes.ATX_2 -> 2
            MarkdownElementTypes.ATX_3 -> 3
            MarkdownElementTypes.ATX_4 -> 4
            MarkdownElementTypes.ATX_5 -> 5
            else -> 6
        }
    )
}

@Composable
private fun MarkdownNode(
    node: ASTNode,
    content: String,
    modifier: Modifier = Modifier,
    onClickCitation: (String) -> Unit = {},
    listLevel: Int = 0
) {
    when (node.type) {
        // 文件根节点
        MarkdownElementTypes.MARKDOWN_FILE -> {
            node.children.fastForEach { child ->
                MarkdownNode(
                    node = child, content = content, modifier = modifier, onClickCitation = onClickCitation
                )
            }
        }

        // 段落
        MarkdownElementTypes.PARAGRAPH -> {
            Paragraph(
                node = node, content = content, modifier = modifier, onClickCitation = onClickCitation
            )
        }

        // 标题
        MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3, MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6 -> {
            val style = HeaderStyle.fromMarkdownType(
                type = node.type,
                fontSizeRatio = LocalSettings.current.displaySetting.fontSizeRatio,
            )
            val headingPadding = HeaderStyle.verticalPadding(node.type)
            ProvideTextStyle(value = LocalTextStyle.current.merge(style)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    node.children.fastForEach { node ->
                        if (node.type == MarkdownTokenTypes.ATX_CONTENT) {
                            Paragraph(
                                node = node,
                                content = content,
                                onClickCitation = onClickCitation,
                                modifier = modifier.padding(vertical = headingPadding),
                                trim = true,
                            )
                        }
                    }
                }
            }
        }

        // 列表
        MarkdownElementTypes.UNORDERED_LIST -> {
            UnorderedListNode(
                node = node,
                content = content,
                modifier = modifier,
                onClickCitation = onClickCitation,
                level = listLevel
            )
        }

        MarkdownElementTypes.ORDERED_LIST -> {
            OrderedListNode(
                node = node,
                content = content,
                modifier = modifier,
                onClickCitation = onClickCitation,
                level = listLevel
            )
        }

        // Checkbox
        GFMTokenTypes.CHECK_BOX -> {
            val isChecked = node.getTextInNode(content).trim() == "[x]"
            Surface(
                shape = RoundedCornerShape(2.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                modifier = modifier,
            ) {
                Box(
                    modifier = Modifier
                        .padding(2.dp)
                        .size(LocalTextStyle.current.fontSize.toDp() * 0.8f),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isChecked) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // 引用块
        MarkdownElementTypes.BLOCK_QUOTE -> {
            ProvideTextStyle(LocalTextStyle.current.copy(fontStyle = FontStyle.Italic)) {
                val borderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                val bgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                Column(
                    modifier = Modifier
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                color = bgColor, size = size
                            )
                            drawRect(
                                color = borderColor, size = Size(10f, size.height)
                            )
                        }
                        .padding(8.dp)) {
                    node.children.fastForEach { child ->
                        MarkdownNode(
                            node = child, content = content, onClickCitation = onClickCitation
                        )
                    }
                }
            }
        }

        // 链接
        MarkdownElementTypes.INLINE_LINK -> {
            val linkText = node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)
                ?.findChildOfTypeRecursive(GFMTokenTypes.GFM_AUTOLINK, MarkdownTokenTypes.TEXT)?.getTextInNode(content)
                ?: ""
            val linkDest =
                node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content) ?: ""
            val context = LocalContext.current
            Text(
                text = linkText,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = modifier.clickable {
                    val intent = Intent(Intent.ACTION_VIEW, linkDest.toUri())
                    context.startActivity(intent)
                })
        }

        // 加粗和斜体
        MarkdownElementTypes.EMPH -> {
            ProvideTextStyle(TextStyle(fontStyle = FontStyle.Italic)) {
                node.children.fastForEach { child ->
                    MarkdownNode(
                        node = child, content = content, modifier = modifier, onClickCitation = onClickCitation
                    )
                }
            }
        }

        MarkdownElementTypes.STRONG -> {
            ProvideTextStyle(TextStyle(fontWeight = FontWeight.Bold)) {
                node.children.fastForEach { child ->
                    MarkdownNode(
                        node = child, content = content, modifier = modifier, onClickCitation = onClickCitation
                    )
                }
            }
        }

        // GFM 特殊元素
        GFMElementTypes.STRIKETHROUGH -> {
            Text(
                text = node.getTextInNode(content), textDecoration = TextDecoration.LineThrough, modifier = modifier
            )
        }

        GFMElementTypes.TABLE -> {
            TableNode(node = node, content = content, modifier = modifier)
        }

        MarkdownTokenTypes.HORIZONTAL_RULE -> {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )
        }

        // 图片
        MarkdownElementTypes.IMAGE -> {
            val altText = node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)?.getTextInNode(content) ?: ""
            val imageUrl =
                node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content) ?: ""
            Column(
                modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 这里可以使用Coil等图片加载库加载图片
                ZoomableAsyncImage(
                    model = imageUrl,
                    contentDescription = altText,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .widthIn(min = 120.dp)
                        .heightIn(min = 120.dp),
                )
            }
        }

        GFMElementTypes.INLINE_MATH -> {
            val formula = node.getTextInNode(content)
            val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
            if (enableLatexRendering) {
                MathInline(
                    formula, modifier = modifier.padding(horizontal = 1.dp),
                    fontSize = LocalTextStyle.current.fontSize
                )
            } else {
                Text(
                    text = formula,
                    fontFamily = FontFamily.Monospace,
                    modifier = modifier.padding(horizontal = 1.dp)
                )
            }
        }

        GFMElementTypes.BLOCK_MATH -> {
            val formula = node.getTextInNode(content)
            val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
            if (enableLatexRendering) {
                MathBlock(
                    formula, modifier = modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    fontSize = LocalTextStyle.current.fontSize
                )
            } else {
                Text(
                    text = formula,
                    fontFamily = FontFamily.Monospace,
                    modifier = modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }
        }

        MarkdownElementTypes.CODE_SPAN -> {
            val code = node.getTextInNode(content).trim('`')
            Text(
                text = code, fontFamily = JetbrainsMono, style = LocalTextStyle.current.copy(fontFeatureSettings = "'calt' 0, 'liga' 0, 'clig' 0"), modifier = modifier
            )
        }

        MarkdownElementTypes.CODE_BLOCK -> {
            val code = node.getTextInNode(content)
            Text(
                text = code,
                modifier = modifier,
            )
        }

        // 代码块
        MarkdownElementTypes.CODE_FENCE -> {
            // 这里不能直接取CODE_FENCE_CONTENT的内容，因为首行indent没有包含在内
            // 因此，需要往上找到最后一个EOL元素，用它来作为代码块的起始offset
            val contentStartIndex = node.children.indexOfFirst { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
            if (contentStartIndex == -1) return
            val eolElement =
                node.children.subList(0, contentStartIndex).findLast { it.type == MarkdownTokenTypes.EOL } ?: return
            val codeContentStartOffset = eolElement.endOffset
            val codeContentEndOffset =
                node.children.findLast { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }?.endOffset ?: return
            val code = content.substring(
                codeContentStartOffset, codeContentEndOffset
            ).trimIndent()

            val language =
                node.findChildOfTypeRecursive(MarkdownTokenTypes.FENCE_LANG)?.getTextInNode(content) ?: "plaintext"
            val hasEnd = node.findChildOfTypeRecursive(MarkdownTokenTypes.CODE_FENCE_END) != null

            HighlightCodeBlock(
                code = code,
                language = language,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .fillMaxWidth(),
                completeCodeBlock = hasEnd
            )
        }

        MarkdownTokenTypes.TEXT -> {
            val text = node.getTextInNode(content)
            Text(
                text = text,
                modifier = modifier,
            )
        }

        MarkdownElementTypes.HTML_BLOCK -> {
            val text = node.getTextInNode(content)
            SimpleHtmlBlock(
                html = text, modifier = modifier
            )
        }

        // 其他类型的节点，递归处理子节点
        else -> {
            // 递归处理其他节点的子节点
            node.children.fastForEach { child ->
                MarkdownNode(
                    node = child, content = content, modifier = modifier, onClickCitation = onClickCitation
                )
            }
        }
    }
}

@Composable
private fun UnorderedListNode(
    node: ASTNode,
    content: String,
    modifier: Modifier = Modifier,
    onClickCitation: (String) -> Unit = {},
    level: Int = 0
) {
    val bulletStyle = when (level % 3) {
        0 -> "• "
        1 -> "◦ "
        else -> "▪ "
    }

    Column(
        modifier = modifier.padding(start = (level * 8).dp)
    ) {
        node.children.fastForEach { child ->
            if (child.type == MarkdownElementTypes.LIST_ITEM) {
                ListItemNode(
                    node = child,
                    content = content,
                    bulletText = bulletStyle,
                    onClickCitation = onClickCitation,
                    level = level
                )
            }
        }
    }
}

@Composable
private fun OrderedListNode(
    node: ASTNode,
    content: String,
    modifier: Modifier = Modifier,
    onClickCitation: (String) -> Unit = {},
    level: Int = 0
) {
    Column(modifier.padding(start = (level * 8).dp)) {
        var index = 1
        node.children.fastForEach { child ->
            if (child.type == MarkdownElementTypes.LIST_ITEM) {
                val numberText =
                    child.findChildOfTypeRecursive(MarkdownTokenTypes.LIST_NUMBER)?.getTextInNode(content) ?: "$index. "
                ListItemNode(
                    node = child,
                    content = content,
                    bulletText = numberText,
                    onClickCitation = onClickCitation,
                    level = level
                )
                index++
            }
        }
    }
}

@Composable
private fun ListItemNode(
    node: ASTNode, content: String, bulletText: String, onClickCitation: (String) -> Unit = {}, level: Int
) {
    Column {
        // 分离列表项的直接内容和嵌套列表
        val (directContent, nestedLists) = separateContentAndLists(node)
        // directContent 渲染处理
        if (directContent.isNotEmpty()) {
            Row {
                Text(
                    text = bulletText,
                    modifier = Modifier.alignByBaseline(),
                    color = MaterialTheme.colorScheme.primary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    directContent.fastForEach { contentChild ->
                        MarkdownNode(
                            node = contentChild,
                            content = content,
                            onClickCitation = onClickCitation,
                            listLevel = level,
                        )
                    }
                }
            }
        }
        // nestedLists 渲染处理
        nestedLists.fastForEach { nestedList ->
            MarkdownNode(
                node = nestedList, content = content, onClickCitation = onClickCitation, listLevel = level + 1 // 增加层级
            )
        }
    }
}

// 分离列表项的直接内容和嵌套列表
private fun separateContentAndLists(listItemNode: ASTNode): Pair<List<ASTNode>, List<ASTNode>> {
    val directContent = mutableListOf<ASTNode>()
    val nestedLists = mutableListOf<ASTNode>()
    listItemNode.children.fastForEach { child ->
        when (child.type) {
            MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST -> {
                nestedLists.add(child)
            }

            else -> {
                directContent.add(child)
            }
        }
    }
    return directContent to nestedLists
}

@Composable
private fun Paragraph(
    node: ASTNode,
    content: String,
    trim: Boolean = false,
    onClickCitation: (String) -> Unit = {},
    modifier: Modifier,
) {
    // dumpAst(node, content)
    if (node.findChildOfTypeRecursive(MarkdownElementTypes.IMAGE, GFMElementTypes.BLOCK_MATH) != null) {
        FlowRow(modifier = modifier) {
            node.children.fastForEach { child ->
                MarkdownNode(
                    node = child, content = content, onClickCitation = onClickCitation
                )
            }
        }
        return
    }

    val colorScheme = MaterialTheme.colorScheme
    val inlineContents = remember {
        mutableStateMapOf<String, InlineTextContent>()
    }
    val hasInlineMath = remember(node) {
        node.findChildOfTypeRecursive(GFMElementTypes.INLINE_MATH) != null
    }
    val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering

    val textStyle = LocalTextStyle.current
    val density = LocalDensity.current
    val latexColorArgb = LocalContentColor.current.toArgb()
    FlowRow(
        modifier = modifier.then(
            if (node.nextSibling() != null) Modifier.padding(bottom = LocalTextStyle.current.fontSize.toDp())
            else Modifier
        )
    ) {
        val annotatedString = remember(content, enableLatexRendering, latexColorArgb) {
            buildAnnotatedString {
                node.children.fastForEach { child ->
                    appendMarkdownNodeContent(
                        node = child,
                        content = content,
                        inlineContents = inlineContents,
                        colorScheme = colorScheme,
                        onClickCitation = onClickCitation,
                        style = textStyle,
                        density = density,
                        trim = trim,
                        enableLatexRendering = enableLatexRendering,
                        latexColorArgb = latexColorArgb,
                    )
                }
            }
        }
        Text(
            text = annotatedString,
            modifier = Modifier,
            inlineContent = inlineContents,
            softWrap = true,
            overflow = TextOverflow.Visible,
            style = LocalTextStyle.current.copy(
                lineHeight = if (hasInlineMath && enableLatexRendering) TextUnit.Unspecified else LocalTextStyle.current.lineHeight
            )
        )
    }
}

@Composable
private fun TableNode(node: ASTNode, content: String, modifier: Modifier = Modifier) {
    // 提取表格的标题行和数据行
    val headerNode = node.children.find { it.type == GFMElementTypes.HEADER }
    val rowNodes = node.children.filter { it.type == GFMElementTypes.ROW }

    // 计算列数（从标题行获取）
    val columnCount = headerNode?.children?.count { it.type == GFMTokenTypes.CELL } ?: 0

    // 检查是否有足够的列来显示表格
    if (columnCount == 0) return

    // 提取表头单元格文本
    val headerCells =
        headerNode?.children?.filter { it.type == GFMTokenTypes.CELL }?.map { it.getTextInNode(content).trim() }
            ?: emptyList()

    // 提取所有行的数据
    val rows = rowNodes.map { rowNode ->
        rowNode.children.filter { it.type == GFMTokenTypes.CELL }.map { it.getTextInNode(content).trim() }
    }

    // 创建表头composable列表
    val headers = List(columnCount) { columnIndex ->
        @Composable {
            MarkdownBlock(
                content = if (columnIndex < headerCells.size) headerCells[columnIndex] else "",
            )
        }
    }

    // 创建行数据composable列表
    val rowComposables = rows.map { rowData ->
        List(columnCount) { columnIndex ->
            @Composable {
                MarkdownBlock(
                    content = if (columnIndex < rowData.size) rowData[columnIndex] else "",
                )
            }
        }
    }

    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 表格原始markdown文本（用于复制）和CSV内容（用于下载）
    val tableMarkdown = remember(node, content) { node.getTextInNode(content).trim() }
    val tableCsv = remember(headerCells, rows) { buildTableCsv(headerCells, rows) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(tableCsv.toByteArray())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // 渲染表格卡片（工具栏 + 表格）
    Column(
        modifier = modifier
            .padding(vertical = 8.dp)
            .clip(MaterialTheme.shapes.large)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "表格",
                fontSize = 12.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val iconSize = 16.dp
                val iconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

                Icon(
                    imageVector = HugeIcons.Copy01,
                    contentDescription = "Copy",
                    tint = iconTint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .onClick {
                            scope.launch {
                                clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText("table", tableMarkdown)))
                            }
                        }
                        .padding(4.dp)
                        .size(iconSize)
                )

                Icon(
                    imageVector = HugeIcons.Download04,
                    contentDescription = "Download",
                    tint = iconTint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .onClick {
                            createDocumentLauncher.launch(
                                "table_${
                                    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                                }.csv"
                            )
                        }
                        .padding(4.dp)
                        .size(iconSize)
                )
            }
        }
        DataTable(
            headers = headers,
            rows = rowComposables,
            columnMinWidths = List(columnCount) { 80.dp },
            columnMaxWidths = List(columnCount) { 200.dp },
            outerBorder = null,
            shape = RectangleShape,
        )
    }
}

// 构建CSV内容，对包含逗号/引号/换行的字段进行转义
private fun buildTableCsv(headerCells: List<String>, rows: List<List<String>>): String {
    fun escape(field: String): String {
        return if (field.any { it == ',' || it == '"' || it == '\n' }) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }
    }
    return buildString {
        appendLine(headerCells.joinToString(",") { escape(it) })
        rows.forEach { row ->
            appendLine(row.joinToString(",") { escape(it) })
        }
    }
}

private fun AnnotatedString.Builder.appendMarkdownNodeContent(
    node: ASTNode,
    content: String,
    trim: Boolean = false,
    inlineContents: MutableMap<String, InlineTextContent>,
    colorScheme: ColorScheme,
    density: Density,
    style: TextStyle,
    enableLatexRendering: Boolean = true,
    latexColorArgb: Int = 0,
    onClickCitation: (String) -> Unit = {},
    linkContext: android.content.Context? = null,
) {
    when {
        node.type == MarkdownTokenTypes.BLOCK_QUOTE -> {}

        node.type == GFMTokenTypes.GFM_AUTOLINK -> {
            val link = node.getTextInNode(content)
            withLink(
                LinkAnnotation.Url(
                    link,
                    linkInteractionListener = { ann ->
                        val u = (ann as? LinkAnnotation.Url)?.url ?: link
                        openWorkspaceLink(u)
                    },
                )
            ) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(link)
                }
            }
        }

        node is LeafASTNode -> {
            val text = node.getTextInNode(content).let {
                if (trim) {
                    it.trim()
                } else {
                    it
                }.replace(BREAK_LINE_REGEX, "\n")
            }
            append(
                text = text,
            )
        }

        node.type == MarkdownElementTypes.EMPH -> {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                node.children.trim(MarkdownTokenTypes.EMPH, 1).fastForEach {
                    appendMarkdownNodeContent(
                        node = it,
                        content = content,
                        inlineContents = inlineContents,
                        colorScheme = colorScheme,
                        density = density,
                        style = style,
                        enableLatexRendering = enableLatexRendering,
                        latexColorArgb = latexColorArgb,
                        onClickCitation = onClickCitation
                    )
                }
            }
        }

        node.type == MarkdownElementTypes.STRONG -> {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                node.children.trim(MarkdownTokenTypes.EMPH, 2).fastForEach {
                    appendMarkdownNodeContent(
                        node = it,
                        content = content,
                        inlineContents = inlineContents,
                        colorScheme = colorScheme,
                        density = density,
                        style = style,
                        enableLatexRendering = enableLatexRendering,
                        latexColorArgb = latexColorArgb,
                        onClickCitation = onClickCitation
                    )
                }
            }
        }

        node.type == GFMElementTypes.STRIKETHROUGH -> {
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                node.children.trim(GFMTokenTypes.TILDE, 2).fastForEach {
                    appendMarkdownNodeContent(
                        node = it,
                        content = content,
                        inlineContents = inlineContents,
                        colorScheme = colorScheme,
                        density = density,
                        style = style,
                        enableLatexRendering = enableLatexRendering,
                        latexColorArgb = latexColorArgb,
                        onClickCitation = onClickCitation
                    )
                }
            }
        }

        node.type == MarkdownElementTypes.INLINE_LINK -> {
            val linkDest =
                node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content) ?: ""
            val linkText = node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)?.getTextInNode(content)
                ?.trim { it == '[' || it == ']' } ?: linkDest
            if (linkText.startsWith("citation,")) {
                // 如果是引用，则特殊处理
                val domain = linkText.substringAfter("citation,")
                val id = linkDest
                if (id.length == 6) {
                    inlineContents.putIfAbsent(
                        "citation:$linkDest", InlineTextContent(
                            placeholder = Placeholder(
                                width = (domain.length * 7).sp,
                                height = 1.em,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                            ), children = {
                                Box(
                                    modifier = Modifier
                                        .clickable {
                                            onClickCitation(id.trim())
                                        }
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                        .background(colorScheme.tertiaryContainer.copy(0.2f)),
                                    contentAlignment = Alignment.Center) {
                                    Text(
                                        text = domain,
                                        modifier = Modifier.wrapContentSize(),
                                        style = TextStyle(
                                            fontSize = 10.sp,
                                            lineHeight = 10.sp,
                                            fontFamily = JetbrainsMono,
                                            color = colorScheme.onTertiaryContainer,
                                            fontWeight = FontWeight.Thin
                                        ),
                                    )
                                }
                            })
                    )
                    appendInlineContent("citation:$linkDest")
                }
            } else {
                withLink(
                    LinkAnnotation.Url(
                        linkDest,
                        linkInteractionListener = { ann ->
                            val u = (ann as? LinkAnnotation.Url)?.url ?: linkDest
                            openWorkspaceLink(u)
                        },
                    )
                ) {
                    withStyle(
                        SpanStyle(
                            color = colorScheme.primary, textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(linkText)
                    }
                }
            }
        }

        node.type == MarkdownElementTypes.AUTOLINK -> {
            val links = node.children.trim(MarkdownTokenTypes.LT, 1).trim(MarkdownTokenTypes.GT, 1)
            links.fastForEach { link ->
                withLink(
                    LinkAnnotation.Url(
                        link.getTextInNode(content),
                        linkInteractionListener = { ann ->
                            val u = (ann as? LinkAnnotation.Url)?.url ?: link.getTextInNode(content)
                            openWorkspaceLink(u)
                        },
                    )
                ) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(link.getTextInNode(content))
                    }
                }
            }
        }

        node.type == MarkdownElementTypes.CODE_SPAN -> {
            val code = node.getTextInNode(content).trim('`')
            withStyle(
                SpanStyle(
                    fontFamily = JetbrainsMono,
                    fontFeatureSettings = "'calt' 0, 'liga' 0, 'clig' 0",
                    fontSize = 0.9.em,
                    color = colorScheme.primary,
                )
            ) {
                append(' ')
                append(code)
                append(' ')
            }
        }

        node.type == GFMElementTypes.INLINE_MATH -> {
            val formula = node.getTextInNode(content)
            if (enableLatexRendering) {
                val fontSizePx = with(density) { style.fontSize.toPx() }
                // 将过长的行内公式按顶层运算符水平拆分为多段，每段最大宽度限制为字号的两倍，
                // 使其能在文本流中换行，避免单体公式超出可用宽度被挤出屏幕
                val drawables = splitLatex(
                    latex = formula,
                    maxWidthPx = fontSizePx * 2,
                    fontSize = fontSizePx,
                    color = latexColorArgb,
                )
                if (drawables.isEmpty()) {
                    // 拆分失败时回退为单体内联渲染
                    appendInlineContent(formula, "[Latex]")
                    val (width, height) = with(density) {
                        assumeLatexSize(
                            latex = formula, fontSize = fontSizePx
                        ).let {
                            it.width().toSp() to it.height().toSp()
                        }
                    }
                    inlineContents.putIfAbsent(/* key = */ formula,/* value = */ InlineTextContent(
                        placeholder = Placeholder(
                            width = width,
                            height = height,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                        ), children = {
                            MathInline(
                                latex = formula, modifier = Modifier
                            )
                        })
                    )
                } else {
                    drawables.forEachIndexed { index, drawable ->
                        // 段间插入零宽空格，提供换行点
                        if (index > 0) append('\u200B')
                        val key = "latex:${formula.hashCode()}:$index"
                        appendInlineContent(key, "[Latex]")
                        val (width, height) = with(density) {
                            drawable.bounds.width().toSp() to drawable.bounds.height().toSp()
                        }
                        inlineContents.putIfAbsent(
                            key, InlineTextContent(
                                placeholder = Placeholder(
                                    width = width,
                                    height = height,
                                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                                ), children = {
                                    LatexDrawable(drawable = drawable)
                                })
                        )
                    }
                }
            } else {
                // 禁用 LaTeX 渲染时，以等宽字体显示原始公式
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 0.95.em,
                    )
                ) {
                    append(formula)
                }
            }
        }

        // 其他类型继续递归处理
        else -> {
            node.children.fastForEach {
                appendMarkdownNodeContent(
                    node = it,
                    content = content,
                    inlineContents = inlineContents,
                    colorScheme = colorScheme,
                    density = density,
                    style = style,
                    enableLatexRendering = enableLatexRendering,
                    latexColorArgb = latexColorArgb,
                    onClickCitation = onClickCitation
                )
            }
        }
    }
}

private fun ASTNode.getTextInNode(text: String): String {
    return text.substring(startOffset, endOffset)
}

private fun ASTNode.getTextInNode(text: String, type: IElementType): String {
    var startOffset = -1
    var endOffset = -1
    children.fastForEach {
        if (it.type == type) {
            if (startOffset == -1) {
                startOffset = it.startOffset
            }
            endOffset = it.endOffset
        }
    }
    if (startOffset == -1 || endOffset == -1) {
        return ""
    }
    return text.substring(startOffset, endOffset)
}

private fun ASTNode.nextSibling(): ASTNode? {
    val brother = this.parent?.children ?: return null
    for (i in brother.indices) {
        if (brother[i] == this) {
            if (i + 1 < brother.size) {
                return brother[i + 1]
            }
        }
    }
    return null
}

private fun ASTNode.findChildOfTypeRecursive(vararg types: IElementType): ASTNode? {
    if (this.type in types) return this
    for (child in children) {
        val result = child.findChildOfTypeRecursive(*types)
        if (result != null) return result
    }
    return null
}

private fun ASTNode.traverseChildren(
    action: (ASTNode) -> Unit
) {
    children.fastForEach { child ->
        action(child)
        child.traverseChildren(action)
    }
}

private fun List<ASTNode>.trim(type: IElementType, size: Int): List<ASTNode> {
    if (this.isEmpty() || size <= 0) return this
    var start = 0
    var end = this.size
    // 从头裁剪
    var trimmed = 0
    while (start < end && trimmed < size && this[start].type == type) {
        start++
        trimmed++
    }
    // 从尾裁剪
    trimmed = 0
    while (end > start && trimmed < size && this[end - 1].type == type) {
        end--
        trimmed++
    }
    return this.subList(start, end)
}
