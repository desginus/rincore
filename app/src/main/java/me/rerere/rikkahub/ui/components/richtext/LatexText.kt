/* 【域 F·主题渲染】 — 消息/文档渲染 | 地图: docs/APP_MAP.md §F */
package me.rerere.rikkahub.ui.components.richtext

/* ───【原版对齐】LatexText.kt | 基线: 原版 2.5.1
 * v4.8.119 差异: 渲染失败回落去壳显示 (原版回落显示 $x$ 残壳);
 * v4.8.128 差异: processLatex 接 LatexCommandSanitizer — 编号/元命令清洗
 * (\tag 家族整条击杀防护, 用户实证 "中间那个公式渲染错误")。
 * ───────────────────────────────────────────────────────────────*/

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import org.intellij.markdown.util.LatexCommandSanitizer
import ru.noties.jlatexmath.JLatexMathDrawable
import ru.noties.jlatexmath.JLatexMathSplitter

fun assumeLatexSize(latex: String, fontSize: Float): Rect {
    return runCatching {
        // v4.8.128: 与渲染同源 (processLatex 含编号/元命令清洗) — 尺寸预估与
        // 实际渲染用同一串, 否则 \tag 公式预估 0 尺寸、渲染 0 尺寸双失败
        JLatexMathDrawable.builder(processLatex(latex))
            .textSize(fontSize)
            .padding(0)
            .build()
            .bounds
    }.getOrElse { Rect(0, 0, 0, 0) }
}

@Composable
fun LatexText(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current
) {
    val style = style.merge(
        fontSize = fontSize,
        color = color
    )
    val density = LocalDensity.current

    val drawable = remember(latex, fontSize, style) {
        runCatching {
            with(density) {
                getLatexDrawable(
                    latex = processLatex(latex),
                    fontSize = fontSize.toPx(),
                    color = style.color.toArgb(),
                    background = style.background.toArgb()
                )
            }
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    if (drawable != null) {
        with(density) {
            Canvas(
                modifier = modifier
                    .size(
                        width = drawable.bounds.width().toDp(),
                        height = drawable.bounds.height().toDp()
                    )
            ) {
                drawable.draw(drawContext.canvas.nativeCanvas)
            }
        }
    } else {
        // v4.8.119: 渲染失败回落显示去壳公式 (processLatex 剥掉 $…$/\\(…\\) 定界符),
        // 旧实现回落显示原文 `$x$` — 用户看到美元符残壳, 误以为"渲染没触发"。
        Text(
            text = processLatex(latex),
            style = style,
            modifier = modifier
        )
    }
}

fun getLatexDrawable(
    latex: String,
    fontSize: Float,
    color: Int,
    background: Int
): JLatexMathDrawable? {
    return runCatching {
        JLatexMathDrawable.builder(processLatex(latex))
            .textSize(fontSize)
            .color(color)
            .background(background)
            .padding(0)
            .align(JLatexMathDrawable.ALIGN_LEFT)
            .build()
    }.onFailure {
        it.printStackTrace()
    }.getOrNull()
}

/**
 * 将一条行内公式按顶层运算符水平拆分为多段 Drawable，
 * 以便在文本流中换行，避免单体公式过长被挤出屏幕。
 * 拆分失败时返回空列表，调用方需自行回退。
 */
fun splitLatex(
    latex: String,
    maxWidthPx: Float,
    fontSize: Float,
    color: Int
): List<JLatexMathDrawable> {
    return runCatching {
        JLatexMathSplitter.split(processLatex(latex), maxWidthPx, fontSize, color)
    }.onFailure {
        it.printStackTrace()
    }.getOrElse { emptyList() }
}

@Composable
fun LatexDrawable(
    drawable: JLatexMathDrawable,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    with(density) {
        Canvas(
            modifier = modifier.size(
                width = drawable.bounds.width().toDp(),
                height = drawable.bounds.height().toDp()
            )
        ) {
            drawable.draw(drawContext.canvas.nativeCanvas)
        }
    }
}

private val inlineDollarRegex = Regex("""^\$(.*?)\$""", RegexOption.DOT_MATCHES_ALL)
private val displayDollarRegex = Regex("""^\$\$(.*?)\$\$""", RegexOption.DOT_MATCHES_ALL)
private val inlineParenRegex = Regex("""^\\\((.*?)\\\)""", RegexOption.DOT_MATCHES_ALL)
private val displayBracketRegex = Regex("""^\\\[(.*?)\\\]""", RegexOption.DOT_MATCHES_ALL)

private fun processLatex(latex: String): String {
    val trimmed = latex.trim()
    val content = when {
        displayDollarRegex.matches(trimmed) ->
            displayDollarRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        inlineDollarRegex.matches(trimmed) ->
            inlineDollarRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        displayBracketRegex.matches(trimmed) ->
            displayBracketRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        inlineParenRegex.matches(trimmed) ->
            inlineParenRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        else -> trimmed
    }
    // v4.8.128 (用户实证 \tag 整条击杀): 编号/元命令清洗 — 见 LatexCommandSanitizer
    // (jlatexmath 对 \tag/\label/\nonumber/\notag/\begin{equation} 零容忍, 一个即
    // 整条 ParseException → 回落原文; 清洗后编号保留为 \quad(...), 其余删除)
    return stripLatexSpacing(LatexCommandSanitizer.sanitize(content))
}

/**
 * v5.3.1 ★用户缺陷三（`[2pt]` / `[6pt]` 当正文显示）真根因：
 *
 * 模型经常输出 pdfLaTeX 风格的**可选间距参数**，例如
 *     `\begin{cases} a & b \\[2pt] c & d \end{cases}`
 * 其中的 `\\[2pt]`（=换行 + 2pt 额外间距）是**排版引擎参数**，
 * 不是数学内容。JLatexMath 不认这个语法 → 解析失败 → 整条公式回落原文，
 * 用户就看到正文里孤零零的 `[2pt]` / `[6pt]`。
 *
 * 处理：把 `\\[<数字><单位>]` 整体替换成 `\\`（保留换行，丢掉间距），
 * 单位覆盖 pdfLaTeX 常用的 pt / mm / cm / in / ex / em / mu。
 */
private val LATEX_SPACING_PARAM = Regex("""\\\\\[\s*[\d.]+\s*(?:pt|mm|cm|in|ex|em|mu)\s*]""")

internal fun stripLatexSpacing(latex: String): String =
    latex.replace(LATEX_SPACING_PARAM, "\\\\")
