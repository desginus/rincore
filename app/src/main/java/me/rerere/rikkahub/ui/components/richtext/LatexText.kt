/* 【域 F·主题渲染】 — 消息/文档渲染 | 地图: docs/APP_MAP.md §F */
package me.rerere.rikkahub.ui.components.richtext

/* ───【原版对齐】LatexText.kt | 基线: 原版 2.5.1
 * v4.8.119 差异: 渲染失败回落去壳显示 (原版回落显示 $x$ 残壳);
 * v4.8.128 差异: processLatex 接 LatexCommandSanitizer — 编号/元命令清洗
 * (\tag 家族整条击杀防护, 用户实证 "中间那个公式渲染错误")。
 * ───────────────────────────────────────────────────────────────*/

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
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
    }.getOrElse {
        // v5.3.15：失败时**不能返回 0×0** —— 行内排版会按 0 尺寸占位，
        // 而 LatexText 现在会渲染错误块（有高度），尺寸预估与真实绘制不一致
        // 会把整行文字挤成一团。给一个保守的最小占位（可见即可）。
        android.graphics.Rect(0, 0, (fontSize * 6f).toInt().coerceAtLeast(96), (fontSize * 1.6f).toInt())
    }
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

    // ══════════════════════════════════════════════════════════════════════
    // v5.3.15 ★★★ 用户实测缺陷二：**失败必须可见**（原本是静默空白）
    //
    // 真根因：jlatexmath 遇到不支持的宏（如 \xRightarrow）时**不抛异常**，而是
    // 产出 bounds 为 0×0 的 drawable。旧代码判据是 `drawable != null` → 走进
    // 正常分支 → Canvas 尺寸 0.dp×0.dp → 屏幕上什么都没有，也没有任何提示。
    // 用户原话：「当前空白表现比报错严重，属于静默失败。」
    //
    // 现在的判据三层：
    //   1) 宏白名单预检命中 → 不进渲染管线，直接错误块；
    //   2) 抛异常 → 错误块（带异常消息）；
    //   3) **返回非 null 但 bounds ≤ 0** → 同样判失败（这是本次的真病灶）。
    // 错误块内容：红色原式 + 错误消息 + 出错位置。
    // ══════════════════════════════════════════════════════════════════════
    val processed = remember(latex) { processLatex(latex) }

    val render = remember(processed, fontSize, style, density) {
        val unsupported = LatexCommandSanitizer.unsupportedMacros(processed)
        if (unsupported.isNotEmpty()) {
            LatexRender.Failed(
                reason = "含渲染器不支持的宏：${unsupported.joinToString(" ")}",
                position = processed.indexOf(unsupported.first()).takeIf { it >= 0 },
            )
        } else {
            runCatching {
                with(density) {
                    getLatexDrawable(
                        latex = processed,
                        fontSize = fontSize.toPx(),
                        color = style.color.toArgb(),
                        background = style.background.toArgb()
                    )
                }
            }.fold(
                onSuccess = { d ->
                    if (d != null && d.bounds.width() > 0 && d.bounds.height() > 0) {
                        LatexRender.Ok(d)
                    } else {
                        LatexRender.Failed(
                            reason = "渲染器产出空图形（0×0）—— 该公式含不支持的语法，已拦截静默空白",
                            position = null,
                        )
                    }
                },
                onFailure = { e ->
                    LatexRender.Failed(
                        reason = e.message?.take(160)?.ifBlank { null }
                            ?: (e::class.java.simpleName ?: "未知错误"),
                        position = null,
                    )
                },
            )
        }
    }

    when (val r = render) {
        is LatexRender.Ok -> with(density) {
            Canvas(
                modifier = modifier
                    .size(
                        width = r.drawable.bounds.width().toDp(),
                        height = r.drawable.bounds.height().toDp()
                    )
            ) {
                r.drawable.draw(drawContext.canvas.nativeCanvas)
            }
        }

        // v5.3.18 ★用户定案：**渲染失败就安静回落原文**，不要红框、不要错误消息。
        // 用户原话：「它渲染错了，它正常就像最开始一样悄咪咪的就行了」——
        // 显眼的错误块比问题本身更干扰阅读；失败信息只写日志，不占版面。
        is LatexRender.Failed -> {
            Log.w("LatexText", "render failed: ${r.reason} | latex=${processed.take(200)}")
            Text(text = processed, style = style, modifier = modifier)
        }
    }
}

/** v5.3.15：公式渲染结果 —— 三态，**不存在"静默什么都不画"的第四态**。 */
private sealed interface LatexRender {
    data class Ok(val drawable: JLatexMathDrawable) : LatexRender
    data class Failed(val reason: String, val position: Int?) : LatexRender
}

// v5.3.18：`LatexErrorBlock`（红框 + 错误消息 + 位置）已删除。
// 理由：用户明确要求失败时安静回落原文，不要显眼提示。失败原因改记 Log.w。


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
    // v5.3.18：间距参数剥离已并入 LatexCommandSanitizer.sanitize()（纯 Kotlin 可单测）。
    // 旧的本地 stripLatexSpacing 用 `Regex.replace(regex, "\\\\")` —— 替换串里的 `\`
    // 会被当转义符，把 `\\[4pt]` 变成**单个 `\`** → 紧跟换行 → 整条公式解析失败。
    // 那是「5.0.12 能渲染、后来渲染不了」的真凶，本版已彻底移除。
    return LatexCommandSanitizer.sanitize(content)
}

// v5.3.18：原 v5.3.1 的 `LATEX_SPACING_PARAM` + `stripLatexSpacing()` 已删除 ——
// 实现搬到 markdown 模块 LatexCommandSanitizer.stripSpacingParams()（手写扫描器，
// 不涉及替换串转义），并修正了「`\\[4pt]` 被替换成单个 `\` 导致整条公式解析失败」的老 bug。

