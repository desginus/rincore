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
 * 根因: CC 网关对 OpenAI Chat Completions 图片格式严格校验 —
 * ① data:image/gif (GIF 保持原样分支) 被拒; ② BitmapFactory 解不出的
 * 格式 (SVG/ICO/损坏图) 走编码失败分支, 发送空 text 块被拒 Invalid
 * input; ③ 失败重试 4 次 → 用户感知"无报错硬等卡死"。
 * OpenCode/DeepSeek 网关宽容所以同图正常; Cherry Studio 统一转 JPEG
 * 所以正常 — 本 transformer 复刻该策略。
 *
 * 行为: JPEG/PNG/WebP 原样放行; GIF/其他可解码格式转 JPEG 静态帧;
 * 不可解码格式剔除图片块并留文本备注 (绝不发空块)。仅当用户在
 * 设置-偏好-网络开启"Command Code 图片兼容"且当前 key 为 user_ 时生效。
 */
object CCImageCompatTransformer : InputMessageTransformer {
    private const val TAG = "CCImageCompat"
    private const val MAX_DIMENSION = 10_000
    private const val MAX_PIXELS = 16_000_000L
    private const val JPEG_QUALITY = 85
    private val SUPPORTED_MIME = setOf("image/jpeg", "image/png", "image/webp")

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (!ctx.settings.ccImageCompat) return messages
        // 仅 Command Code 通道生效 (user_ key); 关闭或 OpenCode 通道保持原状
        if (!ctx.settings.opencodeApiKey.startsWith("user_", ignoreCase = true)) return messages

        var converted = 0
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
                val dataUri = runCatching { toJpegDataUri(part.url) }.getOrNull()
                if (dataUri != null) {
                    converted++
                    if (dataUri !== part.url) newParts.add(part.copy(url = dataUri)) else newParts.add(part)
                } else {
                    skipped++
                    notes.add("[图片已跳过: ${maskUrl(part.url)} 格式不被 Command Code 网关支持]")
                }
            }
            // 纯图消息全被剔时保留备注, 防止 content 数组为空
            if (notes.isNotEmpty()) newParts.add(UIMessagePart.Text(notes.joinToString("\n")))
            msg.copy(parts = newParts)
        }
        if (converted > 0 || skipped > 0) {
            Log.i(TAG, "converted=$converted skipped=$skipped")
        }
        return out
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
                if (mime in SUPPORTED_MIME) return@withContext url
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
        var pixels = w.toLong() * h
        while (pixels / (sample.toLong() * sample) > MAX_PIXELS ||
            maxOf(w, h) / sample > MAX_DIMENSION
        ) sample *= 2
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
