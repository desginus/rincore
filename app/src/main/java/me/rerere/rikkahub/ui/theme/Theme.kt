package me.rerere.rikkahub.ui.theme

/* ───【域 F·主题渲染】Theme.kt
 * 职责: 主题装配 (预设/自定义解析 → ColorScheme 应用)
 * 常用改动: 主题解析 → findThemeById; 应用 → RikkaHubTheme
 * 问题定位: 主题不生效/id 回退 → 本文件 + PresetTheme
 * 基线: 原版移植 + 自研 | 地图: docs/APP_MAP.md §F | 历史: .claude/skills/rincore-bug-record
 * ───────────────────────────────────────────────────────────────*/

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
import me.rerere.rikkahub.ui.hooks.rememberCurrentColorMode
import me.rerere.rikkahub.ui.hooks.rememberUserSettingsState
import androidx.compose.runtime.getValue

private val ExtendLightColors = lightExtendColors()
private val ExtendDarkColors = darkExtendColors()
val LocalExtendColors = compositionLocalOf { ExtendLightColors }

val LocalDarkMode = compositionLocalOf { false }

private val AMOLED_DARK_BACKGROUND = Color(0xFF000000)

@Serializable
enum class ColorMode {
    SYSTEM,
    LIGHT,
    DARK
}

@Composable
fun RikkahubTheme(
    colorMode: ColorMode = rememberCurrentColorMode(),
    content: @Composable () -> Unit
) {
    val settings by rememberUserSettingsState()

    val darkTheme = when (colorMode) {
        ColorMode.SYSTEM -> isSystemInDarkTheme()
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }
    val amoledDarkMode by rememberAmoledDarkMode()

    val colorScheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> {
            val theme = findThemeById(settings.themeId, settings.customThemes)
                ?: findPresetTheme(settings.themeId)
            theme.getColorScheme(dark = darkTheme)
        }
    }
    val colorSchemeConverted = remember(darkTheme, amoledDarkMode, colorScheme) {
        if (darkTheme && amoledDarkMode) {
            colorScheme.copy(
                background = AMOLED_DARK_BACKGROUND,
                surface = AMOLED_DARK_BACKGROUND,
            )
        } else {
            colorScheme
        }
    }
    val extendColors = if (darkTheme) ExtendDarkColors else ExtendLightColors

    // 更新状态栏图标颜色
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(view, darkTheme) {
            // v5.3.6 ★用户崩溃报告（5.3.5 / me.rincore.app.hub / Xiaomi / Android 17）：
            //   java.lang.ClassCastException: me.rerere.rikkahub.RikkaHubApp cannot be cast to android.app.Activity
            //   at ThemeKt.RikkahubTheme$lambda$3$0(Theme.kt:91)
            //   ← FloatingX AppHost.moveTo ← FxControlImpl.<init> ← FloatingX.install
            //   ← FloatingWindow.kt:37
            // 真根因：这里写的是 `(view.context as Activity).window` 硬转换，而**悬浮窗**
            // 的 ComposeView 宿主是 FloatingX 的 AppHost，它的 context 是 Application
            // （FloatingWindow.kt 里 `appHost(context.applicationContext as Application)`），
            // 不是 Activity → 一渲染悬浮窗内容就崩。
            // 修：逐层查找真正的 Activity；找不到（悬浮窗/Service/Application 宿主）就
            // 跳过系统栏颜色处理 —— 这类宿主本来也没有 window 状态栏。
            val activity = view.context.findActivity()
            if (activity == null) {
                onDispose { }
            } else {
                val window = activity.window
                val controller = WindowCompat.getInsetsController(window, view)
                val previousLightStatusBars = controller.isAppearanceLightStatusBars
                val previousLightNavigationBars = controller.isAppearanceLightNavigationBars
                controller.apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
                onDispose {
                    // 嵌套主题（如终端的深色主题）退出时恢复原有系统栏图标颜色。
                    controller.isAppearanceLightStatusBars = previousLightStatusBars
                    controller.isAppearanceLightNavigationBars = previousLightNavigationBars
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalDarkMode provides darkTheme,
        LocalExtendColors provides extendColors,
        // LocalOverscrollFactory provides null   // v5.0.22: 对齐上游 3.0.0（上游此行注释=启用系统默认 overscroll）
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorSchemeConverted,
            typography = Typography,
            content = content,
            motionScheme = MotionScheme.expressive()
        )
    }
}

val MaterialTheme.extendColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendColors.current

/**
 * v5.3.6：从任意 Context 逐层找出真正的 Activity。
 *
 * 悬浮窗（FloatingX AppHost）、Service、Application 这类宿主里
 * `view.context` 不是 Activity，硬转会抛 ClassCastException。
 * 找不到就返回 null，由调用方跳过与 window 相关的操作。
 */
internal fun android.content.Context.findActivity(): Activity? {
    var ctx: android.content.Context = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
