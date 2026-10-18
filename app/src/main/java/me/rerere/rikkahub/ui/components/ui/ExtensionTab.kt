/* 【域 E·设置体系】 — UI 组件 | 地图: docs/APP_MAP.md §E */
/* ───【上游对齐】ExtensionTab | 取自上游 3.0.0 ExtensionSelector.kt 的标签页枚举
 * 用途: AssistantExtensionsPage / ExtensionSelector 的四个扩展页签（快捷消息/模式注入/世界书/技能）
 * ───────────────────────────────────────────────────────────────*/
package me.rerere.rikkahub.ui.components.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R

internal enum class ExtensionTab(
    override val icon: ImageVector,
    @param:StringRes private val labelRes: Int,
) : PagerTab {
    QUICK_MESSAGES(HugeIcons.Zap, R.string.extension_selector_tab_quick_messages),
    MODE_INJECTIONS(HugeIcons.MagicWand01, R.string.extension_selector_tab_mode_injections),
    LOREBOOKS(HugeIcons.Book01, R.string.extension_selector_tab_lorebooks),
    SKILLS(HugeIcons.Puzzle, R.string.extension_selector_tab_skills);

    override val label: String
        @Composable get() = stringResource(labelRes)
}
