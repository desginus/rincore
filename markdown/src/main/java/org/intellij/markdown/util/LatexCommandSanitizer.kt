package org.intellij.markdown.util

/**
 * v4.8.128 (用户实证: "中间那个公式渲染错误 \boxed{} 和 \tag{}"):
 * jlatexmath (rikkahub/jlatexmath-android fork) 对 AMS 编号/元命令**零容忍** —
 * 一个 `\tag{1}` 就让整条公式 ParseException, 渲染端回落"原文带反斜杠"显示
 * (整条公式看起来全坏, 连原生支持的 `\boxed` 一起被误会)。
 *
 * 命令支持面经上游命令矩阵实证 (jlatexmath-1.0.7, jshell 37 例):
 *   FAIL: `\tag` `\tag*` `\label` `\nonumber` `\notag` `\begin{equation}` `\cancel` `\bm`
 *   OK  : `\boxed` `\fbox` `\boldsymbol` `\quad` `\text` `\mathrm` `\displaystyle`
 *         `\begin{align}` `\begin{aligned}` `\begin{cases}` `\overset` `\underset`
 *         `\dfrac` `\tfrac` `\operatorname` `\substack` `\mathscr` `\mathfrak` ...
 *   fork 一致性: PredefinedCommands 已注册 `boxed`(137) `fbox`(136) `boldsymbol`(147)
 *   `quad`(261); 无 `tag`/`label`/`nonumber`/`notag`/equation 环境 (与上游同)。
 *
 * 本件在进渲染器前把「编号/元命令」转为等价可视形态 (核心公式能力不变):
 *   `\tag{X}` / `\tag*{X}`   → `\quad(X)`      (编号保留可见; `\quad` 为核心命令)
 *   `\label{X}`              → 删除             (纯锚点, 无视觉)
 *   `\nonumber` / `\notag`   → 删除             (纯元命令)
 *   `\begin{equation(*)}` / `\end{equation(*)}` → 删除包裹 (保留内容)
 *   `\bm{X}`                 → `\boldsymbol{X}` (别名归一; fork 无 bm 有 boldsymbol)
 * 其余命令零触碰 (含 `\boxed`)。花括号配对用扫描器 (支持 `\tag{\text{a}}` 嵌套
 * 与 `\{` `\}` 转义), 不做朴素正则; 全变换幂等。
 */
object LatexCommandSanitizer {

    fun sanitize(latex: String): String {
        var s = latex
        s = transformBracedCommand(s, "tag", allowStar = true) { inner -> "\\quad($inner)" }
        s = transformBracedCommand(s, "label", allowStar = false) { "" }
        s = removeBareCommand(s, "nonumber")
        s = removeBareCommand(s, "notag")
        s = stripEquationEnvironment(s)
        s = renameBareCommand(s, "bm", "boldsymbol")
        s = normalizeExtensibleArrows(s)
        s = expandFracShorthand(s)
        s = normalizeControlSpace(s)
        return s
    }

    // ═══════════════════════════════════════════════════════════════════════
    // v5.3.16 ★★★用户实测：`\begin{cases}` 分段函数整条失败（0×0 空白）
    //
    // 原文（两个变体都失败）：
    //   `\begin{cases} 0, & x<1 \ \text{或}\ y<1, \\[4pt] ... \end{cases}`
    // 已排除：语法错误、中文 `\text`（case7 正常）、`\\[4pt]`（v5.3.1 已剥离）、
    //        `\le`/`\ge`、公式长度、`\underbrace`。
    // 剩余两个**只在这个公式里出现**的写法，正是 jlatexmath 的处理盲区：
    //
    // ① **控制空格 `\ `（反斜杠+空格）** —— 这是 TeX 原语的 `\def\ {\hskip.3333em}`，
    //    jlatexmath 的 `PredefinedTeXFormula` 只注册了 `\,` `\;` `\:` `\!` 等
    //    **反斜杠+标点**的间距命令，**没有**以空格作命令名的 `\ `。解析器在 `\`
    //    之后遇到空格会直接失败 → 整条公式 ParseException → 0×0。
    //    → 归一为 `\,`（thin space，jlatexmath 确有支持，注释矩阵里 `\sqrt3\,` 在用）。
    //
    // ② **单 token 分数参数 `\dfrac13`** —— TeX 允许 `\frac12` 的紧凑写法（每个参数只
    //    取一个 token）。jlatexmath 的 `\frac` 系列按 `{group}` 解析，遇到裸数字/裸字符
    //    时行为不确定（本公式里 `\dfrac13,` 更易踩空）。→ 统一补成 `\dfrac{1}{3}`。
    //
    // 两者都是**纯增量归一化**：已写规范的公式（`\dfrac{1}{3}`、`\,`）原样不动。
    // ───────────────────────────────────────────────────────────────────────

    /** `\(d|t|c)?frac` 后面跟**非花括号的单个 token**时补上花括号。 */
    private val FRAC_COMMANDS = listOf("dfrac", "tfrac", "cfrac", "frac")

    private fun expandFracShorthand(input: String): String {
        if (input.indexOf("frac") < 0) return input
        val sb = StringBuilder(input.length + 16)
        var i = 0
        while (i < input.length) {
            if (input[i] == '\\') {
                val cmd = FRAC_COMMANDS.firstOrNull { name ->
                    input.startsWith(name, i + 1) &&
                        (i + 1 + name.length >= input.length || !input[i + 1 + name.length].isLetter())
                }
                if (cmd != null) {
                    var j = i + 1 + cmd.length
                    val args = ArrayList<String>(2)
                    var ok = true
                    for (argIndex in 0 until 2) {
                        while (j < input.length && input[j].isWhitespace()) j++
                        if (j >= input.length) { ok = false; break }
                        when {
                            input[j] == '{' -> {
                                val close = findMatchingBrace(input, j)
                                if (close < 0) { ok = false; break }
                                args.add(input.substring(j, close + 1))
                                j = close + 1
                            }
                            input[j] == '\\' -> {
                                var k = j + 1
                                if (k < input.length && !input[k].isLetter()) k++ else
                                    while (k < input.length && input[k].isLetter()) k++
                                args.add("{" + input.substring(j, k) + "}")
                                j = k
                            }
                            else -> {
                                args.add("{" + input[j] + "}")
                                j++
                            }
                        }
                    }
                    if (ok && args.size == 2) {
                        sb.append('\\').append(cmd).append(args[0]).append(args[1])
                        i = j
                        continue
                    }
                }
            }
            sb.append(input[i])
            i++
        }
        return sb.toString()
    }

    /** 控制空格 `\ `（反斜杠+空白）→ `\,`；`\\` 与 `\;` 等原有命令不受影响。 */
    private val CONTROL_SPACE = Regex("""\\\s""")

    private fun normalizeControlSpace(input: String): String {
        if (!input.contains("\\ ")) return input
        // 只把「反斜杠 + 单个空白」换成 thin space；连续两个反斜杠（换行 `\\`）先保护
        val guard = "\u0000"
        var s = input.replace("\\\\", guard)
        s = s.replace(CONTROL_SPACE, "\\,")
        s = s.replace(guard, "\\\\")
        return s
    }

    // ═══════════════════════════════════════════════════════════════════════
    // v5.3.15 ★★★ 双线伸展箭头族归一化（用户实测的确定性缺陷）
    //
    // 实证（同一批次同一会话逐条发送）：
    //   1 行内 `$a \xRightarrow{b} c$`            → 正常
    //   2 块级 `$$a \xRightarrow{b} c$$`          → **空白，无错误提示**
    //   3 块级 `$$a \xrightarrow{b} c$$`          → 正常
    //   4 块级 `$$a \stackrel{b}{\Rightarrow} c$$` → 正常
    //   5 块级 `$$a \Rightarrow b$$`             → 正常
    //   6 \underbrace / 7 \text{中文} / 8 长公式  → 正常
    //   9 行内与块级含 \xRightarrow 均为空白
    // → 排除语法、字体、长度、中文、位置；**问题只在双线伸展箭头这一族**。
    //
    // 原因：jlatexmath（rikkahub fork 1.5）实现了单线 `\xrightarrow` 系列，
    // 但**没有** AMS 的双线 `\xRightarrow` / `\xLeftarrow` / `\xLeftrightarrow`。
    // 它解析失败时不抛异常，而是产出 **0 尺寸 drawable** → 渲染端 `drawable != null`
    // 走正常分支 → Canvas 被画成 0×0 → 用户看到空白且**没有任何提示**（静默失败）。
    //
    // 补偿：把双线族映射到渲染器**确有支持**的组合（注释矩阵已实证
    // `\Rightarrow` `\overset` `\underset` 均 OK），保留「双线 + 上/下标签」语义：
    //   `\xRightarrow{A}`          → `\overset{A}{\Rightarrow}`
    //   `\xRightarrow[B]{A}`       → `\underset{B}{\overset{A}{\Rightarrow}}`
    //   `\xRightarrow`（无参）      → `\Rightarrow`
    // 诚实边界：**不伸展**（渲染器无此能力，无法在 Kotlin 层补出伸展渲染）。
    // 取舍：不空白 > 伸展。若将来换到支持 amsmath 的渲染器，删掉本段即可恢复。
    // ───────────────────────────────────────────────────────────────────────
    /** 命令名 → 渲染器支持的双线符号名（`\` 后不带反斜杠）。 */
    private val DOUBLE_ARROW_COMMANDS = linkedMapOf(
        "xRightarrow" to "Rightarrow",
        "xLeftarrow" to "Leftarrow",
        "xLeftrightarrow" to "Leftrightarrow",
        "xleftrightarrow" to "Leftrightarrow",
        "implies" to "Rightarrow",
        "impliedby" to "Leftarrow",
        "iff" to "Leftrightarrow",
    )

    private fun normalizeExtensibleArrows(input: String): String {
        if (input.indexOf('\\') < 0) return input
        if (DOUBLE_ARROW_COMMANDS.keys.none { input.contains("\\$it") }) return input

        val sb = StringBuilder(input.length + 32)
        var i = 0
        while (i < input.length) {
            if (input[i] == '\\') {
                val matched = DOUBLE_ARROW_COMMANDS.entries.firstOrNull { (name, _) ->
                    input.startsWith(name, i + 1) &&
                        // 命令名后不得紧跟字母（防 \xRightarrows 之类误伤）
                        (i + 1 + name.length >= input.length ||
                            !input[i + 1 + name.length].isLetter())
                }
                if (matched != null) {
                    var j = i + 1 + matched.key.length
                    // 可选 [below]
                    var below: String? = null
                    if (j < input.length && input[j] == '[') {
                        val close = input.indexOf(']', j)
                        if (close > j) {
                            below = input.substring(j + 1, close)
                            j = close + 1
                        }
                    }
                    // 跳过空白
                    while (j < input.length && input[j].isWhitespace()) j++
                    // 可选 {above}
                    var above: String? = null
                    if (j < input.length && input[j] == '{') {
                        val close = findMatchingBrace(input, j)
                        if (close != -1) {
                            above = input.substring(j + 1, close)
                            j = close + 1
                        }
                    }
                    var core = "\\" + matched.value
                    if (above != null) core = "\\overset{$above}{$core}"
                    if (below != null) core = "\\underset{$below}{$core}"
                    sb.append(core)
                    i = j
                    continue
                }
            }
            sb.append(input[i])
            i++
        }
        return sb.toString()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // v5.3.15 ★★ 宏白名单预检（P1-三）
    //
    // 用户要求：「渲染前做宏白名单预检，命中未实现名单直接给提示，不进渲染管线。
    // 当前空白表现比报错严重，属于静默失败。」
    //
    // 这里列出**已实证/高置信**渲染器不支持的宏。命中即由 UI 层直接呈现错误块，
    // 不再交给渲染器（避免它「不抛异常但产出 0 尺寸」的静默失败形态）。
    // 注意：`\tag`/`\label`/`\nonumber`/`\notag` 在 sanitize() 里已被清洗，
    // 不会出现在返回值中，故不列在这里。
    // ───────────────────────────────────────────────────────────────────────
    private val UNSUPPORTED_MACROS = listOf(
        "ce", "cee",             // mhchem 化学式
        "cancel", "bcancel", "xcancel",  // 删除线（上游矩阵实证 FAIL）
        "bm",                    // 已重命名为 boldsymbol，此处防御
        "tikzcd",                // 交换图
    )

    /**
     * 返回 [latex] 中出现的**已知不支持**宏（形如 `\ce`，去重并保持出现顺序）。
     * 调用方命中非空即应直接展示错误，不进渲染管线。
     */
    fun unsupportedMacros(latex: String): List<String> {
        val found = LinkedHashSet<String>()
        for (name in UNSUPPORTED_MACROS) {
            val needle = "\\$name"
            var from = 0
            while (true) {
                val idx = latex.indexOf(needle, from)
                if (idx < 0) break
                val after = idx + needle.length
                // 命令名后不得紧跟字母（`\ce` 不应命中 `\cell`）
                if (after >= latex.length || !latex[after].isLetter()) {
                    found.add(needle)
                    break
                }
                from = idx + 1
            }
        }
        return found.toList()
    }

    /** `\cmd{...}` (可选 `\cmd*{...}`) → transform(inner); 无配对花括号参数则原样保留 */
    private fun transformBracedCommand(
        input: String,
        command: String,
        allowStar: Boolean,
        transform: (String) -> String,
    ): String {
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            if (input[i] == '\\' && matchesCommandAt(input, i, command, allowStar)) {
                val argStart = findBracedArgStart(input, i, command, allowStar)
                if (argStart != -1) {
                    val argEnd = findMatchingBrace(input, argStart)
                    if (argEnd != -1) {
                        sb.append(transform(input.substring(argStart + 1, argEnd)))
                        i = argEnd + 1
                        continue
                    }
                }
            }
            sb.append(input[i])
            i++
        }
        return sb.toString()
    }

    /** 位置 at 处是否为 `\command` (可选 `*`); 命令名后不得紧跟字母 (防 \tagged 误伤) */
    private fun matchesCommandAt(input: String, at: Int, command: String, allowStar: Boolean): Boolean {
        if (!input.startsWith(command, at + 1)) return false
        var j = at + 1 + command.length
        if (allowStar && j < input.length && input[j] == '*') j++
        if (j < input.length && input[j].isLetter()) return false
        return true
    }

    /** 命令名 (可选 `*`) 之后跳过空白, 返回 `{` 的下标; 非花括号参数返回 -1 */
    private fun findBracedArgStart(input: String, at: Int, command: String, allowStar: Boolean): Int {
        var j = at + 1 + command.length
        if (allowStar && j < input.length && input[j] == '*') j++
        while (j < input.length && input[j].isWhitespace()) j++
        return if (j < input.length && input[j] == '{') j else -1
    }

    /** 从 openIdx (必须是 `{`) 起扫描配对 `}`; `\{` `\}` 转义对不参与配对; 未闭合返回 -1 */
    private fun findMatchingBrace(input: String, openIdx: Int): Int {
        var depth = 0
        var i = openIdx
        while (i < input.length) {
            val c = input[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == '{') {
                depth++
            } else if (c == '}') {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        return -1
    }

    private fun removeBareCommand(input: String, command: String): String =
        Regex("\\\\" + Regex.escape(command) + "(?![A-Za-z])").replace(input) { "" }

    private fun renameBareCommand(input: String, from: String, to: String): String =
        Regex("\\\\" + Regex.escape(from) + "(?![A-Za-z])").replace(input) { "\\" + to }

    private fun stripEquationEnvironment(input: String): String {
        var s = Regex("\\\\begin\\{equation\\*?\\}").replace(input) { "" }
        s = Regex("\\\\end\\{equation\\*?\\}").replace(s) { "" }
        return s
    }
}
