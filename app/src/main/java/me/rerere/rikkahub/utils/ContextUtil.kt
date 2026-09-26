/* 【域 L·基础设施】 | 地图: docs/APP_MAP.md §L */
package me.rerere.rikkahub.utils


/* ───【原版对齐】ContextUtil.kt | 差异 ±14 行
 * 来源: 原版移植 + 自研小调整 (未达专项标注阈值, 对齐细节见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.MediaScannerConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap

import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

private const val TAG = "ContextUtil"

/**
 * Read clipboard data as text
 */
fun Context.readClipboardText(): String {
    val clipboardManager =
        getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = clipboardManager.primaryClip ?: return ""
    val item = clip.getItemAt(0) ?: return ""
    return item.text.toString()
}

/**
 * 发起添加群流程
 *
 * @param key 由官网生成的key
 * @return 返回true表示呼起手Q成功，返回false表示呼起失败
 */
fun Context.joinQQGroup(key: String?): Boolean {
    val intent = Intent(Intent.ACTION_VIEW)
    intent.setData(("mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D$key").toUri())
    // 此Flag可根据具体产品需要自定义，如设置，则在加群界面按返回，返回手Q主界面，不设置，按返回会返回到呼起产品界面    //intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
        return true
    } catch (e: java.lang.Exception) {
        // 未安装手Q或安装的版本不支持
        return false
    }
}

/**
 * Write text into clipboard
 */
fun Context.writeClipboardText(text: String) {
    val clipboardManager =
        getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    runCatching {
        clipboardManager.setPrimaryClip(android.content.ClipData.newPlainText("text", text))
        Log.i(TAG, "writeClipboardText: $text")
    }.onFailure {
        Log.e(TAG, "writeClipboardText: $text", it)
        Toast.makeText(this, "Failed to write text into clipboard", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Whether the app has been granted the "Usage access" special permission
 * (android.permission.PACKAGE_USAGE_STATS), required to query screen usage time.
 */
fun Context.hasUsageStatsPermission(): Boolean {
    val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        @Suppress("DEPRECATION") // AppOps 查询无公开替代 API
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

/**
 * Open the system "Usage access" settings page so the user can grant the
 * PACKAGE_USAGE_STATS permission manually.
 */
fun Context.openUsageAccessSettings() {
    runCatching {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }.onFailure {
        Log.e(TAG, "openUsageAccessSettings failed", it)
    }
}

/**
 * Open a url
 */
fun Context.openUrl(url: String) {
    Log.i(TAG, "openUrl: $url")
    runCatching {
        val intent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        intent.launchUrl(this, url.toUri())
    }.onFailure {
        it.printStackTrace()
        Toast.makeText(this, "Failed to open URL: $url", Toast.LENGTH_SHORT).show()
    }
}

fun Context.getActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) {
            return context
        }
        context = context.baseContext
    }
    return null
}

fun Context.getComponentActivity(): ComponentActivity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is ComponentActivity) {
            return context
        }
        context = context.baseContext
    }
    return null
}

fun Context.exportImage(
    activity: Activity,
    bitmap: Bitmap,
    fileName: String = "RikkaHub_${System.currentTimeMillis()}.png"
): Boolean {
    // 检查存储权限（Android 9及以下需要）
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        != PackageManager.PERMISSION_GRANTED
    ) {
        ActivityCompat.requestPermissions(
            activity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1
        )
        Log.w(TAG, "exportImage: WRITE_EXTERNAL_STORAGE not granted")
        return false
    }

    // v4.8.53 修复 ("提示保存成功但相册没有"): ①Q+ 改 MediaStore 两段式
    // (IS_PENDING 1 → 写入 → 置 0), 写入失败删除占位行; ②返回真实结果
    // (原实现静默吞异常, 调用方无条件提示成功 = 假成功); ③日志留痕可查。
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching {
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        }.getOrElse { t ->
            Log.e(TAG, "exportImage: MediaStore insert failed", t)
            null
        } ?: return false

        return try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                outputStream.flush()
            } ?: error("openOutputStream returned null")
            contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null, null
            )
            Log.i(TAG, "exportImage: saved to Pictures/$fileName (MediaStore)")
            true
        } catch (t: Throwable) {
            runCatching { contentResolver.delete(uri, null, null) }
            Log.e(TAG, "exportImage: write failed, pending row removed", t)
            false
        }
    }

    // Android 9及以下直接写入文件
    return try {
        val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        if (!imagesDir.exists()) imagesDir.mkdirs()
        val image = File(imagesDir, fileName)
        FileOutputStream(image).use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        }
        MediaScannerConnection.scanFile(this, arrayOf(image.absolutePath), null, null)
        Log.i(TAG, "exportImage: saved to ${image.absolutePath} (legacy)")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "exportImage legacy write failed", t)
        false
    }
}

/** v4.8.53: 源文件扩展名 → (相册文件名后缀, MIME)。修正原实现一律 .png/image/png
 *  把 JPEG/WebP 改名改型 (部分 ROM 图库对名实不符文件处理异常)。 */
private fun galleryExtAndMime(source: File): Pair<String, String> {
    val ext = source.name.substringAfterLast('.', "").lowercase().ifBlank { "jpg" }
    val mime = when (ext) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "heic", "heif" -> "image/heic"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "image/jpeg"
    }
    return ext to mime
}

fun Context.exportImageFile(
    activity: Activity,
    file: File,
    fileName: String? = null
): Boolean {
    if (!file.exists()) {
        Log.e(TAG, "exportImageFile: source not found: ${file.absolutePath}")
        return false
    }
    val (ext, mime) = galleryExtAndMime(file)
    val resolvedName = fileName ?: "RikkaHub_${System.currentTimeMillis()}.$ext"

    // 检查存储权限（Android 9及以下需要）
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        != PackageManager.PERMISSION_GRANTED
    ) {
        ActivityCompat.requestPermissions(
            activity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1
        )
        Log.w(TAG, "exportImageFile: WRITE_EXTERNAL_STORAGE not granted")
        return false
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, resolvedName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching {
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        }.getOrElse { t ->
            Log.e(TAG, "exportImageFile: MediaStore insert failed", t)
            null
        } ?: return false

        return try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                file.inputStream().use { input -> input.copyTo(outputStream) }
                outputStream.flush()
            } ?: error("openOutputStream returned null")
            contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null, null
            )
            Log.i(TAG, "exportImageFile: saved to Pictures/$resolvedName (MediaStore)")
            true
        } catch (t: Throwable) {
            runCatching { contentResolver.delete(uri, null, null) }
            Log.e(TAG, "exportImageFile: write failed, pending row removed", t)
            false
        }
    }

    // Android 9及以下直接写入文件
    return try {
        val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        if (!imagesDir.exists()) imagesDir.mkdirs()
        val image = File(imagesDir, resolvedName)
        file.copyTo(image, overwrite = true)
        MediaScannerConnection.scanFile(this, arrayOf(image.absolutePath), null, null)
        Log.i(TAG, "exportImageFile: saved to ${image.absolutePath} (legacy)")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "exportImageFile legacy write failed", t)
        false
    }
}
