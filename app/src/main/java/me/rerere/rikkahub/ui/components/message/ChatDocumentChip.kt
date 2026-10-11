/* 【域 F·主题渲染】 — 消息/文档渲染 | 地图: docs/APP_MAP.md §F */
package me.rerere.rikkahub.ui.components.message

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.Share08
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.RenderKind
import me.rerere.rikkahub.ui.components.detectRenderKind
import me.rerere.rikkahub.ui.components.render.RenderEngine
import me.rerere.rikkahub.ui.components.render.RenderResult
import me.rerere.rikkahub.ui.components.render.RenderViewDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import java.io.File

/**
 * v4.8.122 (用户实证: "我发出去的文件点击胶囊窗不会弹出渲染等选项"):
 * 上传/文档胶囊的选项宿主 — 弹层 [渲染 / 分享 / 用其他应用打开] (对齐既有
 * 「编辑过的文件」胶囊 EditedFilesList 的选项范式)。渲染走渲染机
 * RenderEngine.renderSmart; Host 恒驻组合 (open 仅控弹层可见) — 渲染进行中
 * 关闭弹层不丢进度态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentChipHost(
    open: Boolean,
    file: File,
    fileName: String,
    onOpenChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var renderResult by remember { mutableStateOf<RenderResult?>(null) }
    var renderLoading by remember { mutableStateOf(false) }
    val renderable = remember(fileName) { detectRenderKind(fileName) != RenderKind.NONE }

    if (open) {
        ModalBottomSheet(onDismissRequest = { onOpenChange(false) }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (renderable) {
                    Card(
                        onClick = {
                            onOpenChange(false)
                            renderLoading = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        runCatching {
                                            if (!file.exists()) error("文件不存在")
                                            val taskDir = File(context.cacheDir, "doc_chip_render").apply {
                                                deleteRecursively()
                                                mkdirs()
                                            }
                                            renderResult = RenderEngine.renderSmart(file, taskDir, fileName)
                                        }.onFailure {
                                            renderResult = RenderResult.Unsupported(
                                                fileName,
                                                "读取文件失败: ${it.message}",
                                            )
                                        }
                                    }
                                } finally {
                                    renderLoading = false
                                }
                            }
                        },
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = HugeIcons.FileView,
                                contentDescription = null,
                                modifier = Modifier.padding(4.dp),
                            )
                            Text(
                                text = "渲染",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
                Card(
                    onClick = {
                        onOpenChange(false)
                        runCatching {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/octet-stream"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }.onFailure { e ->
                            toaster.show(
                                message = "分享失败: ${e.message?.take(120) ?: "未知错误"}",
                                type = ToastType.Error,
                            )
                        }
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Share08,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp),
                        )
                        Text(
                            text = stringResource(R.string.common_share),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                Card(
                    onClick = {
                        onOpenChange(false)
                        runCatching {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                setDataAndType(uri, "*/*")
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }.onFailure { e ->
                            toaster.show(
                                message = "打开失败: ${e.message?.take(120) ?: "未知错误"}",
                                type = ToastType.Error,
                            )
                        }
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = HugeIcons.File02,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp),
                        )
                        Text(
                            text = "用其他应用打开",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }

    // v4.8.102 同款: 渲染等待加载遮罩 (officecli 真渲染需数秒, 提交瞬间即反馈)
    if (renderLoading && renderResult == null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {},
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

    if (renderResult != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { renderResult = null },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
            ),
        ) {
            RenderViewDialog(
                result = renderResult!!,
                onDismiss = { renderResult = null },
            )
        }
    }
}
