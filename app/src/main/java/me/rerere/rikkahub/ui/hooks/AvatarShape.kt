/* 【域 L·基础设施】 — UI 基础 | 地图: docs/APP_MAP.md §L */
package me.rerere.rikkahub.ui.hooks

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape

/**
 * 5.0.0 头像形状（用户排除令）：
 * 上游 3.0.0 在 loading 态使用 MaterialShapes.Cookie6Sided 旋转形（类齿轮/锯齿边缘）——
 * 明确排除。本应用头像恒为圆形，loading 态也不切换形状。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun rememberAvatarShape(loading: Boolean): Shape = CircleShape
