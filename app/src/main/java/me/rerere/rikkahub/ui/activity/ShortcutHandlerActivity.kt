package me.rerere.rikkahub.ui.activity


/* ───【原版对齐】ShortcutHandlerActivity.kt | 差异 ±42 行
 * 来源: 原版移植 + 自研小调整 (未达专项标注阈值, 对齐细节见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.RouteActivity
import java.io.File

class ShortcutHandlerActivity : ComponentActivity() {

    private var photoURI: Uri? = null

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            photoURI?.let { uri ->
                val intent = Intent(this, RouteActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_STREAM, uri)
                    // Bug #4 修复: singleTask 模式复用现有 Activity
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
            }
        }
        // Bug #4 修复: 延迟 finish，确保 URI 已被 RouteActivity 读取
        // RouteActivity(singleTask) 会通过 onNewIntent 或 onCreate 接收 intent
        // FLAG_GRANT_READ_URI_PERMISSION 确保跨 Activity URI 权限
        finish()
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            launchCamera()
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun launchCamera() {
        val imageFile = File(cacheDir, "shortcut_camera_image.jpg")
        photoURI = FileProvider.getUriForFile(
            this,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            imageFile
        )
        photoURI?.let { uri ->
            // 授予 RouteActivity 读取此 URI 的权限
            grantUriPermission(
                "${BuildConfig.APPLICATION_ID}",
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            takePictureLauncher.launch(uri)
        } ?: finish()
    }
}
