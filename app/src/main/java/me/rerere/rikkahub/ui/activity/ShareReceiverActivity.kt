@file:Suppress("DEPRECATION") // getParcelableExtra 平台 API 无替代
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

        // 复制到私有缓存 (外部 URI 授权在 finish 后失效) — IO 线程, 不阻塞冷启动
        lifecycleScope.launch(Dispatchers.IO) {
            val localUris = fileUris.mapNotNull { copyToLocal(it) }
            withContext(Dispatchers.Main) {
                forwardToRoute(action, localUris, text, type)
            }
        }
    }

    private fun forwardToRoute(action: String?, localUris: List<Uri>, text: String, type: String?) {
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
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(Intent.EXTRA_TEXT, text)
            when {
                localUris.size == 1 -> putExtra(Intent.EXTRA_STREAM, localUris[0])
                localUris.size > 1 -> putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM, ArrayList(localUris)
                )
            }
        }
        intent?.clipData?.let { clip -> forward.clipData = clip }

        startActivity(forward)

        // 延迟清理: 先让 RouteActivity 的启动事务落地, 再清中转任务 (隔离后互不影响, 此为第二层保险)
        Handler(Looper.getMainLooper()).postDelayed({ finishAndRemoveTask() }, 600)
    }

    /**
     * 将外部 content:// / file:// URI 复制到应用私有缓存, 返回 file:// URI。
     * 避免 finish() 后 URI 授权被回收导致 RouteActivity 无法读取。
     */
    private fun copyToLocal(sourceUri: Uri): Uri? {
        return try {
            val dir = File(this.cacheDir, "shared_incoming").apply { mkdirs() }

            // 获取文件名
            val fileName = contentResolver.query(sourceUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex("_display_name")
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            } ?: sourceUri.lastPathSegment ?: "shared_file"

            val destFile = File(dir, fileName)
            contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Uri.fromFile(destFile)
        } catch (e: Exception) {
            android.util.Log.w("ShareReceiver", "copyToLocal failed: $sourceUri", e)
            null
        }
    }
}
