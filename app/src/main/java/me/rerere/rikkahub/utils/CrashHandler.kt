/* 【域 L·基础设施】 | 地图: docs/APP_MAP.md §L */
package me.rerere.rikkahub.utils


/* ───【原版对齐】CrashHandler.kt | 差异 ±13 行
 * 来源: 原版移植 + 自研小调整 (未达专项标注阈值, 对齐细节见对齐地图)
 * v5.0.9 追加: 崩溃报告 Markdown 全量落盘（filesDir/crashes/crash_*.md + 共享下载目录
 *   Download/RinCore-crashes/）+ listReports/exportToDownloads/shareIntent。
 *   目的: 崩溃对话框复制文本不可靠（设备剪贴板限制/事件随机）——Markdown 文件是
 *   唯一稳定反馈通道（崩溃后 SafeMode 页与 设置→崩溃报告 页同入口）。
 * ───────────────────────────────────────────────────────────────*/
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.edit
import me.rerere.rikkahub.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val TAG = "CrashHandler"
private const val PREFS_NAME = "crash_handler"
private const val KEY_CRASHED = "crashed"
private const val KEY_STACKTRACE = "stacktrace"
private const val MAX_STACKTRACE_LENGTH = 8000
private const val MAX_HISTORY_FILES = 30
private const val DOWNLOAD_DIR_NAME = "RinCore-crashes"

object CrashHandler {
    fun install(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            markCrashed(appContext, thread, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun hasCrashed(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CRASHED, false)
    }

    fun getStackTrace(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STACKTRACE, null)
    }

    fun clearCrashed(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_CRASHED).remove(KEY_STACKTRACE) }
    }

    /** 崩溃报告目录（Markdown 全量；含历史 .log 兼容） */
    fun reportsDir(context: Context): File =
        File(context.filesDir, "crashes").apply { mkdirs() }

    /** 全部报告（新 → 旧） */
    fun listReports(context: Context): List<File> =
        reportsDir(context).listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** 最新一份报告 */
    fun latestReport(context: Context): File? = listReports(context).firstOrNull()

    /** 导出到共享下载目录（Download/RinCore-crashes/）——应用无法打开时用户仍可从文件管理器取。
     *  返回展示用路径；失败返回 null。 */
    fun exportToDownloads(context: Context, file: File): String? = runCatching {
        val name = file.name
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_DIR_NAME",
                )
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching null
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            "下载/$DOWNLOAD_DIR_NAME/$name"
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                DOWNLOAD_DIR_NAME,
            ).apply { mkdirs() }
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            "下载/$DOWNLOAD_DIR_NAME/$name"
        }
    }.onFailure { Log.e(TAG, "exportToDownloads failed", it) }.getOrNull()

    /** 分享报告（ACTION_SEND + FileProvider；file_paths 已覆盖 filesDir 全域） */
    fun shareIntent(context: Context, file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "RinCore 崩溃报告 ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun markCrashed(context: Context, thread: Thread, throwable: Throwable) {
        val full = buildMarkdownReport(thread, throwable)
        // 崩溃历史持久化到文件 (Markdown 全量; 保留最近 MAX_HISTORY_FILES 份)
        runCatching {
            val dir = reportsDir(context)
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "crash_$ts.md")
            file.writeText(full)
            exportToDownloads(context, file)
            dir.listFiles()?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_HISTORY_FILES)?.forEach { it.delete() }
        }.onFailure { Log.e(TAG, "persist crash report failed", it) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putBoolean(KEY_CRASHED, true)
                putString(KEY_STACKTRACE, full.take(MAX_STACKTRACE_LENGTH))
            } // commit() 同步写入，确保崩溃前写完
    }

    /** 构建 Markdown 崩溃报告（全量堆栈 + 环境信息；stackTraceToString 含全部 Caused by 链） */
    private fun buildMarkdownReport(thread: Thread, throwable: Throwable): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
            .format(Date())
        return buildString {
            appendLine("# RinCore 崩溃报告")
            appendLine()
            appendLine("- 时间: $ts (+08:00)")
            appendLine("- 版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            appendLine("- 设备: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("- Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("- 线程: ${thread.name}")
            appendLine()
            appendLine("## 完整堆栈")
            appendLine()
            appendLine("```")
            appendLine(throwable.stackTraceToString())
            appendLine("```")
        }
    }
}
