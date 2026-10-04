/* 【域 F·主题渲染】 — UI 组件 | 地图: docs/APP_MAP.md §F */
package me.rerere.rikkahub.ui.components.render

/* ───【自研】RenderRequestHost.kt — 沙箱→软件 文档渲染请求总线 (v4.8.98, 一体化)
 * 链路: 沙箱 `rin render <path>` → 桥端点 /render → RouteActivity intent
 *       → RenderRequestBus → 本 Host 在应用内弹原生渲染弹窗 (渲染机统一处理)。
 * 渲染在 IO 线程; 关闭弹窗即清请求。
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import java.io.File

object RenderRequestBus {
    private val _path = MutableStateFlow<String?>(null)
    val path: StateFlow<String?> = _path.asStateFlow()

    /** 请求打开某个宿主文件路径的原生渲染弹窗 (重复请求后者覆盖前者)。 */
    fun request(hostPath: String) {
        _path.value = hostPath
    }

    fun clear() {
        _path.value = null
    }
}

/** 顶层挂载 (RouteActivity): 观察总线 → IO 渲染 → 原生弹窗。 */
@Composable
fun RenderRequestHost() {
    val path by RenderRequestBus.path.collectAsState()
    val current = path ?: return
    val context = LocalContext.current
    var result by remember(current) { mutableStateOf<RenderResult?>(null) }

    LaunchedEffect(current) {
        result = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(current)
                val taskDir = File(context.cacheDir, "render_rin").apply {
                    deleteRecursively()
                    mkdirs()
                }
                RenderEngine.renderSmart(file, taskDir, file.name)
            }.getOrElse { RenderResult.Unsupported(File(current).name, "无法解析该文档内容") }
        }
    }

    result?.let { r ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { RenderRequestBus.clear() },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
            ),
        ) {
            RenderViewDialog(
                result = r,
                onDismiss = { RenderRequestBus.clear() },
            )
        }
    }

    // v4.8.102: 渲染中加载标志 (officecli 真渲染需数秒)
    if (result == null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { RenderRequestBus.clear() },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        text = stringResource(R.string.render_loading),
                        color = Color.White,
                    )
                }
            }
        }
    }
}
