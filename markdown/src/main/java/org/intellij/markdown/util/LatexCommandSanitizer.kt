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
        return s
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
