@file:Suppress("DEPRECATION") // getParcelableExtra 平台 API 无替代
/* 【域 L·基础设施】 | 地图: docs/APP_MAP.md §L */
package me.rerere.rikkahub.ui.activity


/* ───【自研】ShareReceiverActivity.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * v4.5.26: 分享冷启动修复 — 任务隔离 + VIEW 支持 + 后台复制 + 延迟清理
 * ───────────────────────────────────────────────────────────────*/
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.RouteActivity
import java.io.File

/**
 * 透明中转 Activity: 接收外部 ACTION_SEND / SEND_MULTIPLE / VIEW / PROCESS_TEXT,
 * 将文件复制到应用私有缓存目录后转发给 RouteActivity (singleTask)。
 *
 * v4.5.26 冷启动修复 (三层):
 * 1. taskAffinity="" (Manifest) — 中转任务与主应用任务亲和性隔离。
 *    此前单靠 default affinity, 冷启动时 RouteActivity (singleTask) 会被
 *    "同 affinity 已有任务"规则收进中转任务, 随后 finishAndRemoveTask()
 *    连同目标一起移除 → 分享拉不起 (热启动无此问题, 因为实例已在主任务)。
 * 2. 文件复制移出主线程 — 慢速 provider (网盘/云文档) 不再阻塞冷启动。
 * 3. finishAndRemoveTask 延迟 600ms — 让启动事务先落地, 消除清理竞争。
 *
 * VIEW (用 RinCore 打开) 与 SEND (分享到 RinCore) 统一归一为 SEND 转发,
 * RouteActivity 侧零改动复用既有链路。
 */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 重建场景 (进程恢复) 不重复转发, 避免双重入聊
        if (savedInstanceState != null) { finishAndRemoveTask(); return }
        val sourceIntent = intent ?: run { finishAndRemoveTask(); return }

        val action = sourceIntent.action
        val type = sourceIntent.type
        val text: String = when (action) {
            Intent.ACTION_PROCESS_TEXT ->
                sourceIntent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: ""
            else -> sourceIntent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        }

        // 收集待复制文件 URI: SEND / SEND_MULTIPLE / VIEW (用 RinCore 打开) 三态归一
        val fileUris: List<Uri> = when (action) {
            Intent.ACTION_SEND ->
                listOfNotNull(sourceIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE ->
                sourceIntent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            Intent.ACTION_VIEW ->
                listOfNotNull(
                    sourceIntent.data ?: sourceIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                )
            else -> emptyList()
        }

        // 复制到私有缓存 (外部 URI 授权在 finish 后失效) — IO 线程, 不阻塞冷启动。
        // v4.8.112 (用户实证"小概率空分享"): 复制失败不再静默丢弃 — 该 URI 原样保底
        // 转发 (带读授权旗标), 并在有保底项时不做任务移除 (授权随任务存活)。
        lifecycleScope.launch(Dispatchers.IO) {
            var fallbackCount = 0
            val forwardedUris = ArrayList<Uri>(fileUris.size)
            for (source in fileUris) {
                val local = copyToLocal(source)
                if (local != null) {
                    forwardedUris.add(local)
                } else {
                    fallbackCount++
                    forwardedUris.add(source)
                }
            }
            withContext(Dispatchers.Main) {
                forwardToRoute(action, forwardedUris, text, type, hasFallback = fallbackCount > 0)
            }
        }
    }

    private fun forwardToRoute(
        action: String?,
        localUris: List<Uri>,
        text: String,
        type: String?,
        hasFallback: Boolean = false,
    ) {
        // SEND / SEND_MULTIPLE / VIEW / PROCESS_TEXT 统一为 SEND; 其他 action (TRANSLATE 等) 透传
        val normalizedAction = when (action) {
            Intent.ACTION_SEND,
            Intent.ACTION_SEND_MULTIPLE,
            Intent.ACTION_VIEW,
            Intent.ACTION_PROCESS_TEXT -> Intent.ACTION_SEND
            else -> action ?: Intent.ACTION_SEND
        }

        val forward = Intent(this, RouteActivity::class.java).apply {
            this.action = normalizedAction
            this.type = type ?: "*/*"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                // v4.8.112: 保底转发的原始 URI 依赖读授权 — 显式携带旗标
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(Intent.EXTRA_TEXT, text)
            when {
                localUris.size == 1 -> putExtra(Intent.EXTRA_STREAM, localUris[0])
                localUris.size > 1 -> putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM, ArrayList(localUris)
                )
            }
        }
        // v4.8.112: clipData 以「实际转发的 URI」重建 — 旧实现透传原始 clipData
        // (引用的全是未复制的原始 URI, finish 后即不可读, 是空分享的疑点之一)。
        if (localUris.isNotEmpty()) {
            runCatching {
                val clip = android.content.ClipData.newRawUri("", localUris[0])
                for (i in 1 until localUris.size) {
                    clip.addItem(android.content.ClipData.Item(localUris[i]))
                }
                forward.clipData = clip
            }
        }

        startActivity(forward)

        // 延迟清理: 先让 RouteActivity 的启动事务落地, 再清中转任务 (隔离后互不影响, 此为第二层保险)。
        // v4.8.112: 含保底原始 URI 时仅 finish() — finishAndRemoveTask 会随任务移除回收读授权,
        // 消费侧 (RouteActivity/ChatPage) 需要读取窗口; 任务 excludeFromRecents 不进最近任务。
        if (hasFallback) {
            Handler(Looper.getMainLooper()).postDelayed({ finish() }, 600)
        } else {
            Handler(Looper.getMainLooper()).postDelayed({ finishAndRemoveTask() }, 600)
        }
    }

    /**
     * 将外部 content:// / file:// URI 复制到应用私有缓存, 返回 file:// URI。
     * 避免 finish() 后 URI 授权被回收导致 RouteActivity 无法读取。
     *
     * v4.8.112 (空分享加固):
     * - 文件名 query 失败不再连带整体失败 (部分 provider 不支持 query);
     * - 文件名消毒 (路径分隔符/非法字符), 防 File(dir, name) 逃逸或创建失败;
     * - 备通道 openFileDescriptor (部分网盘 provider 只支持 fd 读取);
     * - 双通道全失败 → 返回 null, 由调用方以原始 URI 保底转发。
     */
    private fun copyToLocal(sourceUri: Uri): Uri? {
        return try {
            val dir = File(this.cacheDir, "shared_incoming").apply { mkdirs() }

            val rawName = runCatching {
                contentResolver.query(sourceUri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex("_display_name")
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
            }.getOrNull() ?: sourceUri.lastPathSegment ?: "shared_file"
            val fileName = rawName
                .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
                .take(120)
                .ifBlank { "shared_file" }

            val destFile = File(dir, fileName)
            // 主通道: InputStream
            val primaryOk = runCatching {
                val input = contentResolver.openInputStream(sourceUri)
                    ?: error("openInputStream returned null for $sourceUri")
                input.use { i -> destFile.outputStream().use { o -> i.copyTo(o) } }
            }.onFailure { e ->
                android.util.Log.w(TAG_LOG, "copyToLocal primary failed: $sourceUri", e)
            }.isSuccess
            // 备通道: FileDescriptor
            val copied = primaryOk || runCatching {
                contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                    java.io.FileInputStream(pfd.fileDescriptor).use { i ->
                        destFile.outputStream().use { o -> i.copyTo(o) }
                    }
                } ?: error("openFileDescriptor returned null for $sourceUri")
            }.onFailure { e ->
                android.util.Log.w(TAG_LOG, "copyToLocal fallback fd failed: $sourceUri", e)
            }.isSuccess
            if (!copied) {
                runCatching { destFile.delete() }
                return null
            }
            Uri.fromFile(destFile)
        } catch (e: Exception) {
            android.util.Log.w(TAG_LOG, "copyToLocal failed: $sourceUri", e)
            null
        }
    }

    companion object {
        private const val TAG_LOG = "ShareReceiver"
    }
}
