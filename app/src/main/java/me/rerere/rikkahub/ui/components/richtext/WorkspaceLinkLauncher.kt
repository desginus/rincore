package me.rerere.rikkahub.ui.components.richtext


/* ───【自研】WorkspaceLinkLauncher.kt — 原版无此文件
 * v3.15.3: 工作区文件链接点击打开 — 根治 FileUriExposedException 崩溃。
 * 根因: 模型输出的 file:// 链接被 Compose 默认 LinkAnnotation.Url handler
 * 直通 Intent.setData(file://) → StrictMode 主线程崩溃 (file:// 跨进程
 * 共享在 Android 7+ 被系统禁止, 必须 FileProvider content:// 中转)。
 * v3.15.2 host 前缀适配后模型更常输出 file:// 链接, 崩溃面暴露。
 * ───────────────────────────────────────────────────────────────*/
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import me.rerere.rikkahub.utils.isWorkspaceUri
import me.rerere.rikkahub.utils.resolveAnyFile

/**
 * 工作区链接统一打开入口:
 * - workspace:// 与 file:// (含 host 字面前缀) → 解析宿主文件 →
 *   FileProvider content:// URI → ACTION_VIEW (系统应用打开 xlsx/pdf 等)
 * - 解析失败 → Toast 提示 (不崩溃不静默)
 * - http/https 等其他 scheme → 系统默认打开
 */
fun openWorkspaceLink(context: Context, url: String) {
    val trimmed = url.trim()
    if (isWorkspaceUri(trimmed)) {
        val file = runCatching { resolveAnyFile(trimmed) }.getOrNull()
        if (file == null || !file.exists()) {
            Toast.makeText(
                context,
                "文件不存在或已失效: ${trimmed.substringAfterLast('/')}",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        runCatching {
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mimeFor(file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure { e ->
            val msg = if (e is ActivityNotFoundException) {
                "没有可打开该类型文件的应用"
            } else {
                "打开失败: ${e.message ?: "未知错误"}"
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
        return
    }
    // 非 workspace/file 链接 — 系统默认处理 (崩溃面仅在 file:// 直通 Intent)
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(trimmed))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { e ->
        if (e is ActivityNotFoundException) {
            Toast.makeText(context, "没有可打开该链接的应用", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "xlsx", "xls" -> "application/vnd.ms-excel"
    "docx", "doc" -> "application/msword"
    "pdf" -> "application/pdf"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "txt", "md" -> "text/plain"
    "csv" -> "text/csv"
    "zip" -> "application/zip"
    "json" -> "application/json"
    "mp4" -> "video/mp4"
    "mp3" -> "audio/mpeg"
    else -> "*/*"
}
