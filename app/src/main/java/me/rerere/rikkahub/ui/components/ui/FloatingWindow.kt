package me.rerere.rikkahub.ui.components.ui

/* ───【原版对齐】FloatingWindow.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import com.petterp.floatingx.FloatingX
import com.petterp.floatingx.assist.FxGravity
import com.petterp.floatingx.listener.control.IFxAppControl
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
fun FloatingWindow(
    tag: String,
    visibility: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var window: IFxAppControl? by remember { mutableStateOf(null) }

    LaunchedEffect(visibility) {
        if (visibility) {
            window?.show()
        } else {
            window?.hide()
        }
    }

    DisposableEffect(context) {
        window = FloatingX.install {
            setTag(tag)
            setContext(context)
            setGravity(FxGravity.LEFT_OR_BOTTOM)
            setOffsetXY(20f, -20f)
            setEnableAnimation(true)
            setLayoutView(ComposeView(context).apply {
                setContent {
                    RikkahubTheme {
                        content()
                    }
                }
            })
        }
        if (visibility) window?.show() else window?.hide()
        onDispose {
            window?.cancel()
        }
    }
}
