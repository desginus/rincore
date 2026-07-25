package me.rerere.rikkahub.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import me.rerere.rikkahub.RouteActivity
import java.io.File

/**
 * 透明中转 Activity: 接收外部 ACTION_SEND/SEND_MULTIPLE/PROCESS_TEXT,
 * 将文件复制到应用私有缓存目录后转发给 RouteActivity (singleTask)。
 *
 * 解决两个顽固问题:
 * 1. WPS 等应用分享时 FLAG_ACTIVITY_MULTIPLE_TASK 绕过 singleTask
 * 2. 外部 URI 权限在 finish() 后失效, RouteActivity 无法读取文件
 *
 * 方案: 将共享文件复制到 /data/data/.../cache/shared_incoming/,
 * 以 file:// URI 传递给 RouteActivity, 避免跨 Activity URI 权限问题。
 */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent ?: run { finishAndRemoveTask(); return }

        val action = intent.action
        val type = intent.type
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""

        val forward = Intent(this, RouteActivity::class.java).apply {
            this.action = action
            this.type = type
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(Intent.EXTRA_TEXT, text)
        }

        when (action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                val localUri = uri?.let { copyToLocal(it) }
                if (localUri != null) {
                    forward.putExtra(Intent.EXTRA_STREAM, localUri)
                }
                // 复制 clipData 中的 URI
                intent.clipData?.let { clip ->
                    forward.clipData = clip
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                val localUris = uris?.mapNotNull { copyToLocal(it) }
                if (!localUris.isNullOrEmpty()) {
                    forward.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(localUris))
                }
                intent.clipData?.let { clip ->
                    forward.clipData = clip
                }
            }
            Intent.ACTION_PROCESS_TEXT -> {
                val processText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                processText?.let { forward.putExtra(Intent.EXTRA_PROCESS_TEXT, it) }
            }
        }

        startActivity(forward)
        finishAndRemoveTask()
    }

    /**
     * 将外部 content:// URI 复制到应用私有缓存, 返回 file:// URI。
     * 避免 finish() 后 URI 权限被回收导致 RouteActivity 无法读取。
     */
    private fun copyToLocal(sourceUri: Uri): Uri? {
        return try {
            val cacheDir = File(cacheDir, "shared_incoming").apply { mkdirs() }

            // 获取文件名
            val fileName = contentResolver.query(sourceUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex("_display_name")
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            } ?: sourceUri.lastPathSegment ?: "shared_file"

            val destFile = File(cacheDir, fileName)
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
