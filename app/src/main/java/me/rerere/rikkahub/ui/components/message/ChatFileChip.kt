/* 【域 F·主题渲染】 — 消息/文档渲染 | 地图: docs/APP_MAP.md §F */
package me.rerere.rikkahub.ui.components.message

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.FileView
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.RenderKind
import me.rerere.rikkahub.ui.components.detectRenderKind
import me.rerere.rikkahub.ui.components.render.RenderEngine
import me.rerere.rikkahub.ui.components.render.RenderResult
import me.rerere.rikkahub.ui.components.render.RenderViewDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import java.io.File

/**
 * v4.8.122→v4.8.125 (用户定版): 消息内文件胶囊的统一选项宿主 —
 * 弹层 [渲染 / 导出 / 用其他应用打开]。覆盖: 上传文档 (Document) / 视频 / 音频胶囊。
 *
 * v4.8.125 重写要点 (用户复核):
 * - 「分享」与「用其他应用打开」实质同一系统选单 → **删除「分享」**, 保留 3 项;
 * - 宿主恒驻 (open 仅控弹层可见) — 渲染进行中关闭弹层不丢进度态;
 * - `file` 允许为 null (URL 无法解出本地文件/文件已被清理) — 点按任何动作都有
 *   **可见 toast 反馈**, 不再出现"点了没反应";
 * - 「用其他应用打开」按扩展名解析 MIME (原先通配 MIME 会把无关应用全列进来)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileChipOptionsHost(
    open: Boolean,
    file: File?,
    fileName: String,
    onOpenChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var renderResult by remember { mutableStateOf<RenderResult?>(null) }
    var renderLoading by remember { mutableStateOf(false) }
    val renderable = remember(fileName) { detectRenderKind(fileName) != RenderKind.NONE }
    val displayName = fileName.ifBlank { file?.name.orEmpty() }

    fun missingFile(): Boolean {
        val f = file
        if (f == null || !f.exists()) {
            toaster.show(message = "文件不存在或已被清理", type = ToastType.Error)
            return true
        }
        return false
    }

    // v4.8.123: 导出 = 系统另存副本 (CreateDocument)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val f = file
        if (uri == null || f == null) return@rememberLauncherForActivityResult
        runCatching {
            val out = context.contentResolver.openOutputStream(uri) ?: error("无法写入目标位置")
            out.use { output -> f.inputStream().use { input -> input.copyTo(output) } }
            toaster.show(
                message = "已导出: ${displayName.ifBlank { f.name }}",
                type = ToastType.Success,
            )
        }.onFailure { e ->
            toaster.show(
                message = "导出失败: ${e.message?.take(120) ?: "未知错误"}",
                type = ToastType.Error,
            )
        }
    }

    if (open) {
        ModalBottomSheet(onDismissRequest = { onOpenChange(false) }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = displayName.ifBlank { "文件" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (renderable) {
                    Card(
                        onClick = {
                            onOpenChange(false)
                            if (missingFile()) return@Card
                            renderLoading = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        runCatching {
                                            val f = file ?: error("文件不存在")
                                            val taskDir = File(context.cacheDir, "doc_chip_render").apply {
                                                deleteRecursively()
                                                mkdirs()
                                            }
                                            renderResult = RenderEngine.renderSmart(f, taskDir, displayName.ifBlank { f.name })
                                        }.onFailure {
                                            renderResult = RenderResult.Unsupported(
                                                displayName,
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
                        if (missingFile()) return@Card
                        runCatching {
                            exportLauncher.launch(displayName.ifBlank { file?.name ?: "file" })
                        }.onFailure { e ->
                            toaster.show(
                                message = "导出失败: ${e.message?.take(120) ?: "未知错误"}",
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
                            imageVector = HugeIcons.FileImport,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp),
                        )
                        Text(
                            text = stringResource(R.string.common_export),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                Card(
                    onClick = {
                        onOpenChange(false)
                        if (missingFile()) return@Card
                        runCatching {
                            val f = file ?: error("文件不存在")
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                f,
                            )
                            // v4.8.125: 按扩展名解析 MIME (原 "*/*" 会把无关应用全列进来)
                            val ext = (displayName.ifBlank { f.name }).substringAfterLast('.', "").lowercase()
                            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                setDataAndType(uri, mime)
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
