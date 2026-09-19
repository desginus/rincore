package me.rerere.rikkahub.data.operit.market


/* ───【自研】岔路口计划·阶段1 — 商城包下载器
 * 重试链 + sha256 校验 (Operit 市场条目自带 sha256 字段)
 * 来源: RinCore 自研新增
 * ───────────────────────────────────────────────────────────────*/
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

class PackageDownloader(
    private val client: OkHttpClient,
) {
    /** 下载进度回调 (已下载字节 / 总字节, 总字节 -1 表示未知) */
    suspend fun download(
        url: String,
        destFile: File,
        expectedSha256: String,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            destFile.parentFile?.mkdirs()
            var lastError: Throwable? = null
            // 重试链: 最多 3 次下载尝试 (github 偶发拒连自愈)
            for (attempt in 1..MAX_DOWNLOAD_ATTEMPTS) {
                try {
                    doDownload(url, destFile, onProgress)
                    if (expectedSha256.isBlank()) {
                        return@runCatching destFile
                    }
                    val actual = sha256(destFile)
                    if (actual.equals(expectedSha256, ignoreCase = true)) {
                        return@runCatching destFile
                    }
                    // sha256 不符 — 单独再试一次 (避免传输损坏)
                    destFile.delete()
                    throw IllegalStateException("sha256 mismatch: expected=$expectedSha256 actual=$actual")
                } catch (e: Throwable) {
                    lastError = e
                    if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                        kotlinx.coroutines.delay((attempt * 1500L))
                    }
                }
            }
            throw lastError ?: IllegalStateException("download failed after $MAX_DOWNLOAD_ATTEMPTS attempts: $url")
        }
    }

    private fun doDownload(url: String, destFile: File, onProgress: (Long, Long) -> Unit) {
        val request = Request.Builder().url(url).get().header("User-Agent", "RinCore/Market").build()
        // 用一个跟随重定向 + 较长超时的副本 (api.operit.app 会 302 → GitHub)
        val downloadClient = client.newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code} for $url")
            }
            val total = response.body?.contentLength() ?: -1L
            response.body?.byteStream()?.use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        downloaded += n
                        if (total > 0) onProgress(downloaded, total)
                    }
                    output.flush()
                }
            } ?: error("empty body for $url")
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_DOWNLOAD_ATTEMPTS = 3
    }
}
