/* 【渲染器 · RinFrost 自管高斯】 | 地图: docs/APP_MAP.md §E */
package me.rerere.rikkahub.ui.glass

/* ───【自研】RinFrost.kt — v4.8.174 (用户定版 2026-10-17:
 *   "实在不行我们就换技术路线，等效相同就可以，不走这样的磨砂。
 *    我们走高斯模糊可以做到吗？…想一想社区在这种问题时都是怎么处理的")
 * 社区老牌方案 = BlurView / RealtimeBlurView 范式 (Compose 现代实现):
 *   源捕获 (GraphicsLayer.record — 同 haze 的源机制)
 *   → 低分辨率重绘 (1/8, 双线性 = 天然 8px 低通, BlurView 精髓)
 *   → CPU 盒式模糊 (滑动窗口 O(n) ×2 轮 ≈ 高斯; CPU 输出无 GPU 量化网格)
 *   → 上采样绘制 (drawImage filterQuality=High 双线性平滑放大)
 * 全链绕开 haze/Skia RenderEffect GPU 模糊合成链 — 用户实测 (B187/B188):
 * 该链任何半径/噪点/板/保真档组合都产量化采样网格 (锐利方块马赛克), 不可调除。
 * 磨砂管线 (haze) 保留为设置内可选引擎 (D19 旋钮全部保留)。
 * ───────────────────────────────────────────────────────────────*/

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** 自管高斯引擎状态: 源捕获层集合 + 各源窗口锚点 + 重算节流水位 */
public class RinFrostState internal constructor() {
    internal val sources = mutableListOf<GraphicsLayer>()
    internal val origins = mutableMapOf<GraphicsLayer, Offset>()
    internal var lastFrostMs: Long = 0L
}

/** 重算节流 20fps — 磨砂是低频内容, 视觉无感; toImageBitmap 读回有成本 */
private const val FROST_THROTTLE_MS = 50L

/** 低分辨率重绘倍率 (8x = 天然 8px 低通; BlurView 范式核心) */
private const val FROST_DOWNSCALE = 8f

@Composable
public fun rememberRinFrostState(): RinFrostState = remember { RinFrostState() }

/** 自管高斯引擎提供口 (聊天页提供; 空时消费端自动退回磨砂管线) */
public val LocalRinFrostState: ProvidableCompositionLocal<RinFrostState?> =
    compositionLocalOf { null }

/** 源注册: 本节点内容录进共享捕获层 (自身显示照常; 按窗口坐标对齐多源) */
public fun Modifier.rinFrostSource(state: RinFrostState?): Modifier =
    if (state == null) {
        this
    } else {
        composed {
            val layer = rememberGraphicsLayer()
            DisposableEffect(state) {
                state.sources.add(layer)
                state.origins[layer] = Offset.Zero
                onDispose {
                    state.sources.remove(layer)
                    state.origins.remove(layer)
                }
            }
            onGloballyPositioned { state.origins[layer] = it.positionInRoot() }
            drawWithContent {
                layer.record(size = IntSize(size.width.roundToInt(), size.height.roundToInt())) {
                    this@drawWithContent.drawContent()
                }
                drawContent()
            }
        }
    }

/** 消费端: 背后内容 = 1/8 捕获 + CPU 盒式模糊 → 双线性上采样; alphaPercent=模糊层不透明度 */
public fun Modifier.rinFrost(
    state: RinFrostState,
    radiusDp: Int,
    alphaPercent: Int,
): Modifier = composed {
    val density = LocalDensity.current
    val smallLayer = rememberGraphicsLayer()
    var anchor by remember { mutableStateOf(Offset.Zero) }
    var frosted by remember { mutableStateOf<ImageBitmap?>(null) }
    var lastMs by remember { mutableLongStateOf(0L) }
    onGloballyPositioned { anchor = it.positionInRoot() }
    drawBehind {
        val now = SystemClock.elapsedRealtime()
        if (frosted == null || now - lastMs >= FROST_THROTTLE_MS) {
            lastMs = now
            val sw = (size.width / FROST_DOWNSCALE).roundToInt().coerceAtLeast(1)
            val sh = (size.height / FROST_DOWNSCALE).roundToInt().coerceAtLeast(1)
            smallLayer.record(size = IntSize(sw, sh)) {
                scale(1f / FROST_DOWNSCALE, pivot = Offset.Zero) {
                    state.sources.forEach { src ->
                        val off = state.origins[src] ?: Offset.Zero
                        translate(off.x - anchor.x, off.y - anchor.y) {
                            drawLayer(src)
                        }
                    }
                }
            }
            val r = ((radiusDp * density.density / FROST_DOWNSCALE) / 2f)
                .roundToInt().coerceIn(1, 6)
            frosted = rinFrostBoxBlur(smallLayer.toImageBitmap(), r)
        }
        frosted?.let { img ->
            drawImage(
                image = img,
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                alpha = alphaPercent.coerceIn(0, 100) / 100f,
                filterQuality = FilterQuality.High,
            )
        }
    }
}

/** CPU 盒式模糊 ×2 轮 (滑动窗口 O(n)) ≈ 高斯; 输出干净低频渐变, 无 GPU 量化网格 */
private fun rinFrostBoxBlur(src: ImageBitmap, radius: Int): ImageBitmap {
    val abmp0 = src.asAndroidBitmap()
    val abmp = if (abmp0.config == Bitmap.Config.HARDWARE) {
        abmp0.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        abmp0
    }
    val w = abmp.width
    val h = abmp.height
    var pixels = IntArray(w * h)
    abmp.getPixels(pixels, 0, w, 0, 0, w, h)
    val r = radius.coerceIn(1, 12)
    repeat(2) {
        pixels = boxPass(pixels, w, h, r, horizontal = true)
        pixels = boxPass(pixels, w, h, r, horizontal = false)
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    return out.asImageBitmap()
}

/** 单趟盒式模糊 (滑窗增量, 边缘夹取) */
private fun boxPass(src: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean): IntArray {
    val dst = IntArray(src.size)
    val window = 2 * r + 1
    if (horizontal) {
        for (y in 0 until h) {
            var a = 0L
            var rr = 0L
            var g = 0L
            var b = 0L
            for (k in -r..r) {
                val p = src[y * w + k.coerceIn(0, w - 1)]
                a += (p ushr 24) and 0xFF
                rr += (p ushr 16) and 0xFF
                g += (p ushr 8) and 0xFF
                b += p and 0xFF
            }
            for (x in 0 until w) {
                dst[y * w + x] = ((a / window).toInt() shl 24) or
                    ((rr / window).toInt() shl 16) or
                    ((g / window).toInt() shl 8) or
                    (b / window).toInt()
                val po = src[y * w + (x - r).coerceIn(0, w - 1)]
                val pi = src[y * w + (x + r + 1).coerceIn(0, w - 1)]
                a += ((pi ushr 24) and 0xFF) - ((po ushr 24) and 0xFF)
                rr += ((pi ushr 16) and 0xFF) - ((po ushr 16) and 0xFF)
                g += ((pi ushr 8) and 0xFF) - ((po ushr 8) and 0xFF)
                b += (pi and 0xFF) - (po and 0xFF)
            }
        }
    } else {
        for (x in 0 until w) {
            var a = 0L
            var rr = 0L
            var g = 0L
            var b = 0L
            for (k in -r..r) {
                val p = src[k.coerceIn(0, h - 1) * w + x]
                a += (p ushr 24) and 0xFF
                rr += (p ushr 16) and 0xFF
                g += (p ushr 8) and 0xFF
                b += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = ((a / window).toInt() shl 24) or
                    ((rr / window).toInt() shl 16) or
                    ((g / window).toInt() shl 8) or
                    (b / window).toInt()
                val po = src[(y - r).coerceIn(0, h - 1) * w + x]
                val pi = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                a += ((pi ushr 24) and 0xFF) - ((po ushr 24) and 0xFF)
                rr += ((pi ushr 16) and 0xFF) - ((po ushr 16) and 0xFF)
                g += ((pi ushr 8) and 0xFF) - ((po ushr 8) and 0xFF)
                b += (pi and 0xFF) - (po and 0xFF)
            }
        }
    }
    return dst
}
