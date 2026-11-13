package me.rerere.rikkahub.ui.floating

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02
import me.rerere.rikkahub.data.pet.PetPack
import me.rerere.rikkahub.data.pet.PetRepository
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 悬浮球/面板共用的宠物渲染器。
 *
 * 只读 pack.json 的序列帧，不包含任何 DSH Go 原始美术。
 * 状态名做了兼容映射：DSH 包的 typing/thinking/notification 等可直接使用。
 */
@Composable
fun PetSprite(
    pack: PetPack?,
    animationName: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    val repo = remember(context) { PetRepository(context) }
    val animation = pack?.animationFor(animationName)
    val bitmap by produceState<ImageBitmap?>(initialValue = null, pack?.id, animation?.sheet) {
        value = if (pack != null && animation != null) {
            withContext(Dispatchers.IO) { repo.bitmap(pack, animation.sheet) }
        } else null
    }
    var frameIndex by remember(pack?.id, animation?.sheet) { mutableIntStateOf(0) }

    LaunchedEffect(pack?.id, animation?.sheet) {
        frameIndex = 0
        val anim = animation ?: return@LaunchedEffect
        val frames = anim.frames.ifEmpty { listOf(me.rerere.rikkahub.data.pet.PetFrame(0)) }
        while (true) {
            val frame = frames[frameIndex.coerceIn(frames.indices)]
            delay((frame.ms ?: (anim.ms / frames.size).coerceAtLeast(40L)).coerceAtLeast(40L))
            if (!anim.loop && frameIndex >= frames.lastIndex) break
            frameIndex = (frameIndex + 1) % frames.size
        }
    }

    if (pack == null || animation == null || bitmap == null) {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = HugeIcons.Image02,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(24.dp),
            )
        }
        return
    }

    val image = bitmap ?: return
    val frames = animation.frames.ifEmpty { listOf(me.rerere.rikkahub.data.pet.PetFrame(0)) }
    val frame = frames[frameIndex.coerceIn(frames.indices)]

    Canvas(modifier = modifier) {
        val cols = animation.cols.coerceAtLeast(1)
        val srcX = (frame.i % cols) * animation.frameW
        val srcY = (frame.i / cols) * animation.frameH
        val scale = min(
            size.width / animation.frameW.toFloat(),
            size.height / animation.frameH.toFloat(),
        )
        val dstW = (animation.frameW * scale).roundToInt().coerceAtLeast(1)
        val dstH = (animation.frameH * scale).roundToInt().coerceAtLeast(1)
        drawImage(
            image = image,
            srcOffset = IntOffset(srcX, srcY),
            srcSize = IntSize(animation.frameW, animation.frameH),
            dstOffset = IntOffset(
                ((size.width - dstW) / 2f).roundToInt(),
                (size.height - dstH).roundToInt(),
            ),
            dstSize = IntSize(dstW, dstH),
            filterQuality = FilterQuality.None,
            alpha = 1f,
            colorFilter = null,
            blendMode = androidx.compose.ui.graphics.BlendMode.SrcOver,
        )
    }
}

/** 工作/等待/完成/失败 → 宠物动画名；DSH 包缺少某个状态时由 [PetPack.animationFor] 回退。 */
fun FloatingChatUiState.petAnimationName(): String = when {
    generating && pendingTools.isNotEmpty() -> "wait"
    generating && hasReply -> "working"
    generating -> "think"
    failed -> "error"
    hasReply -> "celebrate"
    else -> "idle"
}

private fun PetPack.animationFor(name: String) = when (name) {
    "idle" -> animations["idle"] ?: animations["idle-look"] ?: animations.values.firstOrNull()
    "working" -> animations["working"] ?: animations["typing"] ?: animations["building"] ?: animations["think"]
    "think" -> animations["think"] ?: animations["thinking"] ?: animations["idle"]
    "celebrate" -> animations["celebrate"] ?: animations["happy"] ?: animations["smile-hearts"] ?: animations["idle"]
    "error" -> animations["error"] ?: animations["idle"]
    "wait" -> animations["wait"] ?: animations["notification"] ?: animations["idle"]
    else -> animations[name] ?: animations["idle"] ?: animations.values.firstOrNull()
}
