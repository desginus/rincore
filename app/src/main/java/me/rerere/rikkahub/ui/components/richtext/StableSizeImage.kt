/* 【域 F·主题渲染】 — 消息/文档渲染 | 地图: docs/APP_MAP.md §F */
package me.rerere.rikkahub.ui.components.richtext

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.utils.resolveLocalImageFile

/**
 * v4.8.123 (用户实证: 上滑途中消息跳动/抽动 — "没有成功渲染就留太小"):
 * 加载期预留与最终一致的尺寸, 图片从占位到加载完不再改变布局。
 *
 * 尺寸发现两级:
 *  ① 本地图片 (workspace:// / file:// / 上传) — BitmapFactory 只读头解出
 *     intrinsic 像素尺寸 (微秒级, 结果进 [ImagePixelSizeMemo] 进程内 LRU),
 *     首帧即可按最终尺寸预留;
 *  ② 远端图片 — 首次加载成功后由 [ZoomableAsyncImage] 回填 memo, 同会话
 *     二次出现 (含上滑回看) 即按已知尺寸预留。
 * 显示尺寸口径 = min(自然dp宽, 可用宽) × 等比高, 与加载完成后的既有布局
 * 行为一致 (未知名则回落原来的 min 120dp 占位行为)。
 */
internal object ImagePixelSizeMemo {
    private val map = object : LinkedHashMap<String, IntSize>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntSize>?): Boolean =
            size > 64
    }

    @Synchronized
    fun get(key: String): IntSize? = map[key]

    @Synchronized
    fun put(key: String, value: IntSize) {
        if (key.isNotBlank() && value.width > 0 && value.height > 0) map[key] = value
    }
}

/** 本地图片 intrinsic 像素尺寸 (只读文件头, 不解码整图); 非本地/失败返回 null。 */
internal fun precomputeLocalPixelSize(model: String): IntSize? = runCatching {
    // v4.8.185 (审计重写): 与 Coil 认领层同源 (utils.LocalImageAddress 单一解析点) —
    // 旧实现只走 WorkspaceImageResolver, 对 host 私有直读形态 (upload/chat-media 等)
    // 解析失败 → 这类图片没有首帧尺寸预留 (回弹占位)。
    val file = resolveLocalImageFile(model) ?: return null
    if (!file.isFile) return null
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, opts)
    if (opts.outWidth > 0 && opts.outHeight > 0) IntSize(opts.outWidth, opts.outHeight) else null
}.getOrNull()

@Composable
fun StableSizeImage(
    src: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    minSide: Dp = 120.dp,
    maxHeight: Dp? = null,
) {
    val density = LocalDensity.current.density
    // 本地预读 (memo 命中零 IO; 首见只读文件头) — 首帧即有尺寸, 后续走 memo
    val pixel = remember(src) {
        ImagePixelSizeMemo.get(src) ?: precomputeLocalPixelSize(src)?.also { ImagePixelSizeMemo.put(src, it) }
    }
    BoxWithConstraints(modifier = Modifier) {
        val display = pixel?.let { px ->
            if (px.width <= 0 || px.height <= 0) {
                null
            } else {
                val naturalW = (px.width / density).dp
                val width = minOf(naturalW, maxWidth).coerceAtLeast(minSide)
                var height = (width.value * px.height / px.width).dp.coerceAtLeast(minSide)
                if (maxHeight != null && height > maxHeight) height = maxHeight
                width to height
            }
        }
        if (display != null) {
            ZoomableAsyncImage(
                model = src,
                contentDescription = contentDescription,
                modifier = modifier
                    .size(display.first, display.second)
                    .clip(RoundedCornerShape(8.dp)),
            )
        } else {
            ZoomableAsyncImage(
                model = src,
                contentDescription = contentDescription,
                modifier = modifier
                    .clip(RoundedCornerShape(8.dp))
                    .widthIn(min = minSide)
                    .heightIn(min = minSide),
            )
        }
    }
}
