package me.rerere.rikkahub.ui.theme

/* ───【原版对齐】Type.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import me.rerere.rikkahub.R

val base = Typography()
val Typography = Typography()

// Set of Material typography styles to start with
//val Typography = Typography(
//    displayLargeEmphasized = base.displayLargeEmphasized.copy(
//        fontFamily = GoogleSansFlex.Display.Emphasized.Large,
//        fontWeight = FontWeight.Bold
//    ),
//    displayMediumEmphasized = base.displayMediumEmphasized.copy(
//        fontFamily = GoogleSansFlex.Display.Emphasized.Medium,
//        fontWeight = FontWeight.Bold
//    ),
//    displaySmallEmphasized = base.displaySmallEmphasized.copy(
//        fontFamily = GoogleSansFlex.Display.Emphasized.Large,
//        fontWeight = FontWeight.Bold
//    ),
//    headlineLargeEmphasized = base.headlineLargeEmphasized.copy(
//        fontFamily = GoogleSansFlex.Headline.Emphasized.Large,
//        fontWeight = FontWeight.Bold
//    ),
//    headlineMediumEmphasized = base.headlineMediumEmphasized.copy(
//        fontFamily = GoogleSansFlex.Headline.Emphasized.Medium,
//        fontWeight = FontWeight.Bold
//    ),
//    headlineSmallEmphasized = base.headlineSmallEmphasized.copy(
//        fontFamily = GoogleSansFlex.Headline.Emphasized.Large,
//        fontWeight = FontWeight.Bold
//    ),
//    titleLargeEmphasized = base.titleLargeEmphasized.copy(
//        fontFamily = GoogleSansFlex.Title.Emphasized.Large,
//        fontWeight = FontWeight.Bold
//    ),
//    titleMediumEmphasized = base.titleMediumEmphasized.copy(
//        fontFamily = GoogleSansFlex.Title.Emphasized.Medium,
//        fontWeight = FontWeight.Bold
//    ),
//    titleSmallEmphasized = base.titleSmallEmphasized.copy(
//        fontFamily = GoogleSansFlex.Title.Emphasized.Small,
//        fontWeight = FontWeight.Bold
//    ),
//    bodyLargeEmphasized = base.bodyLargeEmphasized.copy(
//        fontFamily = GoogleSansFlex.Body.Emphasized.Large,
//        fontWeight = FontWeight.Bold
//    ),
//    bodyMediumEmphasized = base.bodyMediumEmphasized.copy(
//        fontFamily = GoogleSansFlex.Body.Emphasized.Medium,
//        fontWeight = FontWeight.Bold
//    ),
//    bodySmallEmphasized = base.bodySmallEmphasized.copy(
//        fontFamily = GoogleSansFlex.Body.Emphasized.Small,
//        fontWeight = FontWeight.Bold
//    ),
//    labelLargeEmphasized = base.labelLargeEmphasized.copy(
//        fontFamily = GoogleSansFlex.Label.Emphasized.Large,
//        fontWeight = FontWeight.Bold
//    ),
//    labelMediumEmphasized = base.labelMediumEmphasized.copy(
//        fontFamily = GoogleSansFlex.Label.Emphasized.Medium,
//        fontWeight = FontWeight.Bold
//    ),
//    labelSmallEmphasized = base.labelSmallEmphasized.copy(
//        fontFamily = GoogleSansFlex.Label.Emphasized.Small,
//        fontWeight = FontWeight.Bold
//    ),
//)

@OptIn(ExperimentalTextApi::class)
val JetbrainsMono = FontFamily(
    Font(
        resId = R.font.jetbrains_mono,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(FontWeight.Normal.weight),
        )
    )
)
