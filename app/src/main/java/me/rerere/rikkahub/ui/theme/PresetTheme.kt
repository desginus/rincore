package me.rerere.rikkahub.ui.theme

/* ───【域 F·主题渲染】PresetTheme.kt
 * 职责: 预设主题注册表 (PresetThemes 列表 + findPresetTheme/findThemeById)
 * 常用改动: 新预设注册 → 列表 + import (色板抄 MinimalTheme 全色板)
 * 问题定位: 主题 id 找不到回退 Sakura → 本文件
 * 基线: 与 2.5.1 逐字节一致 + 自研注册 (DefaultTheme) | 地图: docs/APP_MAP.md §F | 历史: .claude/skills/rincore-bug-record
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import me.rerere.rikkahub.ui.theme.presets.AutumnThemePreset
import me.rerere.rikkahub.ui.theme.presets.BlackThemePreset
import me.rerere.rikkahub.ui.theme.presets.ClaudeThemePreset
import me.rerere.rikkahub.ui.theme.presets.DefaultThemePreset
import me.rerere.rikkahub.ui.theme.presets.MinimalThemePreset
import me.rerere.rikkahub.ui.theme.presets.OceanThemePreset
import me.rerere.rikkahub.ui.theme.presets.SakuraThemePreset
import me.rerere.rikkahub.ui.theme.presets.SpringThemePreset

data class PresetTheme(
    val id: String,
    val name: @Composable () -> Unit,
    val standardLight: ColorScheme,
    val standardDark: ColorScheme,
) {
    fun getColorScheme(dark: Boolean): ColorScheme {
        return if (dark) standardDark else standardLight
    }
}

val PresetThemes by lazy {
    listOf(
        SakuraThemePreset,
        OceanThemePreset,
        SpringThemePreset,
        AutumnThemePreset,
        BlackThemePreset,
        MinimalThemePreset,
        // v4.8.7: 「默认」— 极简白同构色板, tertiary 系换莫兰迪紫
        DefaultThemePreset,
        ClaudeThemePreset,
    )
}

fun findPresetTheme(id: String): PresetTheme {
    return PresetThemes.find { it.id == id } ?: SakuraThemePreset
}

fun findThemeById(id: String, customThemes: List<CustomTheme>): PresetTheme? {
    PresetThemes.find { it.id == id }?.let { return it }
    val custom = customThemes.find { it.id == id } ?: return null
    return PresetTheme(
        id = custom.id,
        name = { androidx.compose.material3.Text(custom.name) },
        standardLight = custom.generateColorScheme(dark = false),
        standardDark = custom.generateColorScheme(dark = true),
    )
}
