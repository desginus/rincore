package me.rerere.rikkahub.data.ai.transformers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import android.media.ExifInterface
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL

/**
 * v3.13.3: Command Code 图片兼容适配 (CC 专属, opt-in)
 *
 * 根因 (社区调查定案, v3.13.5): CC Provider API 上 Go 档模型
 * (DeepSeek V4 Flash) 不接受图片 — pi-commandcode-provider 实测
 * "Go must select generate and reject unsupported images"; OpenAI
 * 兼容层的 reject 表现为 SSE 静默挂起 (CC issue #785 同族, 无
 * header 无报错) → 客户端 25s 判死重试 4 次。PC 端 CLI 正常是因为
 * 官方 Vision 机制: 图永远不进主模型请求, 先侧调用 vision 模型
 * 转文字 (VISION 工具) 再回答。压缩/格式修复 (v3.13.3/4) 无效
 * 因为问题在服务端模型路由, 不在请求体。
 * 本 transformer 复刻 CLI VISION 机制: 图片经 OCR 模型转文字后
 * 替换为文本块, 图不进 CC 请求。需在助手设置配置 OCR 模型
 * (任意 vision 模型, 走任意可用通道)。
 *
 * 行为: JPEG/PNG/WebP 原样放行; GIF/其他可解码格式转 JPEG 静态帧;
 * 不可解码格式剔除图片块并留文本备注 (绝不发空块)。仅当用户在
 * 设置-偏好-网络开启"Command Code 图片兼容"且当前 key 为 user_ 时生效。
 */
object CCImageCompatTransformer : InputMessageTransformer {
    private const val TAG = "CCImageCompat"
    // v3.13.4: 对齐 Cherry Studio 官方压缩策略 (sharp resize 2048 inside +
    // jpeg q85, issue #14061: 60MB payload 致网关无限加载同款事故):
    // 所有图片过尺寸闸门 — 最长边 >2048px 或体积 >1.5MB 即重编码,
    // 小图原样放行不折腾 (用户: 不要压缩太狠; 2048px 为视觉 API 推荐口径,
    // 模型识别无损失, 显示端仍用原图不受影响)
    private const val MAX_DIMENSION = 2048
    private const val REENCODE_BYTES = 1_500_000L
    private const val JPEG_QUALITY = 85
    private val SUPPORTED_MIME = setOf("image/jpeg", "image/png", "image/webp")

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (!ctx.settings.ccImageCompat) return messages
        // 仅 Command Code 通道生效 (user_ key); 关闭或 OpenCode 通道保持原状
        if (!ctx.settings.opencodeApiKey.startsWith("user_", ignoreCase = true)) return messages
        // 图已全部转写过 (上一轮已替换为文字) 的消息跳过
        val hasImages = messages.any { m -> m.parts.any { it is UIMessagePart.Image } }
        if (!hasImages) return messages

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ctx.processingStatus.value = "正在识别图片 (Command Code 模式)..."
            try {
                var described = 0
                var skipped = 0
                val out = messages.map { msg ->
                    if (msg.parts.none { it is UIMessagePart.Image }) return@map msg
                    val notes = mutableListOf<String>()
                    val newParts = mutableListOf<UIMessagePart>()
                    msg.parts.forEach { part ->
                        if (part !is UIMessagePart.Image) {
                            newParts.add(part)
                            return@forEach
                        }
                        // v3.13.5: CLI VISION 工具同款 — 图经 OCR 模型转文字
                        // (LRU 缓存), 替换为文本块; CC Go 档模型 reject 图片,
                        // 图不能进主请求 (服务端行为, 客户端格式修复无效)
                        val ocr = me.rerere.rikkahub.data.ai.transformers.OcrTransformer
                            .performOcr(copyImageForOcr(part))
                        if (ocr.startsWith("[ERROR") || ocr == "[Image]") {
                            skipped++
                            notes.add("[图片未能识别: 请在设置中配置 OCR 模型 (任意 vision 模型)]")
                        } else {
                            described++
                            newParts.add(UIMessagePart.Text(ocr))
                        }
                    }
                    if (notes.isNotEmpty()) newParts.add(UIMessagePart.Text(notes.joinToString("\n")))
                    msg.copy(parts = newParts)
                }
                Log.i(TAG, "described=$described skipped=$skipped")
                out
            } finally {
                ctx.processingStatus.value = null
            }
        }
    }

    /** performOcr 需要 file:// URI (FilesManager 读取), data URI 先落盘 */
    private suspend fun copyImageForOcr(part: UIMessagePart.Image): UIMessagePart.Image {
        if (part.url.startsWith("file://") || part.url.startsWith("content://")) return part
        val bytes = runCatching { toJpegDataUri(part.url) }.getOrNull()
            ?.let { uri ->
                val b64 = uri.substringAfter("base64,", "")
                if (b64.isNotEmpty()) android.util.Base64.decode(b64, android.util.Base64.DEFAULT) else null
            } ?: return part
        val fm = org.koin.java.KoinJavaComponent.getKoin()
            .get<me.rerere.rikkahub.data.files.FilesManager>()
        val uri2 = fm.createChatFilesByByteArrays(listOf(bytes)).first()
        return part.copy(url = uri2.toString())
    }

    /** 返回可直接放入 image_url.url 的 data URI; 已合规则原样返回引用 */
    private suspend fun toJpegDataUri(url: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val bytes: ByteArray = when {
            url.startsWith("data:") -> {
                val mime = url.removePrefix("data:").substringBefore(";").lowercase()
                val b64 = url.substringAfter("base64,", "").let {
                    if (it.isEmpty()) error("data URI 无 base64 载荷")
                    Base64.decode(it, Base64.DEFAULT)
                }
                if (mime in SUPPORTED_MIME && b64.size <= REENCODE_BYTES) {
                    // v3.13.4: 合规 mime 也要过尺寸闸门 — PNG 无损截图可达
                    // 6-8MB, 多图历史重发滚雪球致 header 25s 判死 (Cherry
                    // Studio 同款事故 #14061); 体积小才原样放行
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(b64, 0, b64.size, bounds)
                    if (bounds.outWidth in 1..MAX_DIMENSION && bounds.outHeight in 1..MAX_DIMENSION) {
                        return@withContext url
                    }
                }
                b64
            }
            url.startsWith("file://") -> {
                val f = File(url.toUri().path ?: error("非法文件 URI"))
                if (!f.exists()) error("文件不存在")
                f.readBytes()
            }
            url.startsWith("http") -> URL(url).openConnection().apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }.getInputStream().use { it.readBytes() }
            else -> error("不支持的图片来源: ${maskUrl(url)}")
        }
        encodeJpeg(bytes)
    }

    private fun encodeJpeg(bytes: ByteArray): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("图片无法解码 (SVG/ICO/损坏数据)")
        val sample = calcSampleSize(bounds.outWidth, bounds.outHeight)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: error("图片解码失败")
        val rotated = normalizeByExif(bitmap, bytes)
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (rotated !== bitmap) rotated.recycle()
        bitmap.recycle()
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return "data:image/jpeg;base64,$b64"
    }

    private fun calcSampleSize(w: Int, h: Int): Int {
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= MAX_DIMENSION) sample *= 2
        return sample
    }

    private fun normalizeByExif(bitmap: Bitmap, raw: ByteArray): Bitmap = runCatching {
        val stream = java.io.ByteArrayInputStream(raw)
        val exif = ExifInterface(stream)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val m = android.graphics.Matrix().apply { postRotate(degrees) }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }.getOrDefault(bitmap)

    private fun maskUrl(url: String): String =
        if (url.length <= 48) url else url.take(24) + "..." + url.takeLast(12)

    private fun String.toUri(): android.net.Uri = android.net.Uri.parse(this)
}
