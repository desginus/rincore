package me.rerere.rikkahub.ui.components.richtext

/* ───【原版对齐】MathBlock.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse

@Composable
fun MathInline(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified
) {
    val proceededLatex = latex
    LatexText(
        latex = proceededLatex,
        color = LocalContentColor.current,
        fontSize = fontSize.takeOrElse { LocalTextStyle.current.fontSize },
        modifier = modifier,
    )
}

@Composable
fun MathBlock(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified
) {
    val proceededLatex = latex
    Box(
        modifier = modifier.padding(8.dp)
    ) {
        LatexText(
            latex = proceededLatex,
            color = LocalContentColor.current,
            fontSize = fontSize.takeOrElse { LocalTextStyle.current.fontSize },
            modifier = Modifier
                .align(Alignment.Center)
                .horizontalScroll(
                    rememberScrollState()
                ),
        )
    }
}
