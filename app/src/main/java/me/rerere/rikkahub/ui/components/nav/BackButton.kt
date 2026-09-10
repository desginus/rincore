package me.rerere.rikkahub.ui.components.nav

/* ───【原版对齐】BackButton.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors

@Composable
fun BackButton(modifier: Modifier = Modifier) {
    val navController = LocalNavController.current
    FilledTonalIconButton(
        onClick = {
            navController.popBackStack()
        },
        modifier = modifier,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = CustomColors.listItemColors.containerColor),
    ) {
        Icon(
            imageVector = HugeIcons.ArrowLeft01,
            contentDescription = stringResource(R.string.back)
        )
    }
}
