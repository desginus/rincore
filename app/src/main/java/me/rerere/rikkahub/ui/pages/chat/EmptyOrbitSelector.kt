/* 【域 A·对话核心】 | 地图: docs/APP_MAP.md §A */
package me.rerere.rikkahub.ui.pages.chat

/* ───【自研】EmptyOrbitSelector.kt — v4.8.147 (用户定版)
 * 新聊天/默认页(无消息)双弧轨道选择器:
 *   · 外弧轨道 = 助手 (圆形头像) — 点选 = 切助手 (委托上层 moveConversationToAssistant)
 *   · 内弧轨道 = 项目包 (当前助手的包) — 点选 = 回写本对话 folderId
 *   · 几何: 右上(新增对话按钮下方) → 左下(输入栏左上方), 从大圆裁一段的缓弧 (二次贝塞尔近似);
 *     内弧 = 外弧沿弧法线内缩紧贴。
 *   · 动画: 传送带轮播 — 队列自右上向左下流动, 出左下后立即从右上重现 (%1 环绕)。
 *   · 状态: 当前项 = 图标放大 + 主色描边 + 下方名称备注。
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.uuid.Uuid
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Folder
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.ui.context.LocalSettings
import org.koin.compose.koinInject

private const val ORBIT_DURATION_MS = 26_000
private val ORBIT_EDGE_DP = 52.dp
private val ORBIT_TOP_DP = 44.dp
private val INNER_GAP_DP = 64.dp
private val ICON_DP = 42.dp
private const val BOW_FACTOR = 0.16f

@Composable
fun EmptyOrbitSelector(
    currentFolderId: Uuid?,
    onSelectAssistant: (Uuid) -> Unit,
    onSelectFolder: (Uuid) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    val folderRepo: FolderRepository = koinInject()
    val folders by remember(settings.assistantId) {
        folderRepo.getFoldersOfAssistant(settings.assistantId)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val assistants = settings.assistants

    val transition = rememberInfiniteTransition(label = "orbit")
    val tState = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = ORBIT_DURATION_MS, easing = LinearEasing)),
        label = "orbitT",
    )

    var area by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val edgePx = with(density) { ORBIT_EDGE_DP.toPx() }
    val topPx = with(density) { ORBIT_TOP_DP.toPx() }
    val gapPx = with(density) { INNER_GAP_DP.toPx() }
    val iconPx = with(density) { ICON_DP.toPx() }

    Box(modifier = modifier.fillMaxSize().onSizeChanged { area = it }) {
        if (area.width <= 0 || area.height <= 0) return@Box

        // 外弧: A=右上(新增对话下方) → B=左下(输入栏左上方); C=弦中点沿法线外凸 (缓弧)
        val a = Offset2(area.width - edgePx, topPx)
        val b = Offset2(edgePx, area.height - topPx)
        val abx = b.x - a.x
        val aby = b.y - a.y
        val abLen = hypot(abx, aby).coerceAtLeast(1f)
        // 法线 = 旋转 AB 90° 并指向上左 (外凸方向)
        var nx = (a.y - b.y) / abLen
        var ny = (b.x - a.x) / abLen
        val nLen = sqrt(nx * nx + ny * ny).coerceAtLeast(1e-6f)
        nx /= nLen
        ny /= nLen
        val c = Offset2(
            (a.x + b.x) / 2f + nx * abLen * BOW_FACTOR,
            (a.y + b.y) / 2f + ny * abLen * BOW_FACTOR,
        )
        // 内弧 = 整体沿法线反向 (向弧内侧) 平移, 紧贴外弧
        fun inner(p: Offset2) = Offset2(p.x - nx * gapPx, p.y - ny * gapPx)
        val ai = inner(a)
        val bi = inner(b)
        val ci = inner(c)

        val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)

        Canvas(Modifier.fillMaxSize()) {
            val outerPath = Path().apply {
                moveTo(a.x, a.y)
                quadraticBezierTo(c.x, c.y, b.x, b.y)
            }
            val innerPath = Path().apply {
                moveTo(ai.x, ai.y)
                quadraticBezierTo(ci.x, ci.y, bi.x, bi.y)
            }
            val stroke = Stroke(width = 1.5.dp.toPx())
            drawPath(outerPath, color = trackColor, style = stroke)
            drawPath(innerPath, color = trackColor, style = stroke)
        }

        // 外弧: 助手
        val n = assistants.size
        assistants.forEachIndexed { index, assistant ->
            val selected = assistant.id == settings.assistantId
            OrbitItem(
                tState = tState,
                phaseOffset = if (n > 0) index.toFloat() / n else 0f,
                arcA = a,
                arcC = c,
                arcB = b,
                selected = selected,
                halfIcon = iconPx / 2f,
                label = assistant.name,
                onClick = { onSelectAssistant(assistant.id) },
            ) {
                AssistantCircle(avatar = assistant.avatar, name = assistant.name)
            }
        }

        // 内弧: 项目包 (当前助手)
        val m = folders.size
        folders.forEachIndexed { index, folder ->
            val selected = folder.id == currentFolderId
            OrbitItem(
                tState = tState,
                phaseOffset = if (m > 0) index.toFloat() / m else 0f,
                arcA = ai,
                arcC = ci,
                arcB = bi,
                selected = selected,
                halfIcon = iconPx / 2f,
                label = folder.name,
                onClick = { onSelectFolder(folder.id) },
            ) {
                Box(
                    modifier = Modifier.size(ICON_DP),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = HugeIcons.Folder01,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

private data class Offset2(val x: Float, val y: Float)

/** 二次贝塞尔取样 (与 Canvas 轨迹同一条曲线)。 */
private fun quadPoint(a: Offset2, c: Offset2, b: Offset2, t: Float): Offset2 {
    val u = 1f - t
    return Offset2(
        u * u * a.x + 2f * u * t * c.x + t * t * b.x,
        u * u * a.y + 2f * u * t * c.y + t * t * b.y,
    )
}

/**
 * 轨道项: 图标 (可放大/描边) + 选中时名称备注。
 * 动画值 (tState) 在 graphicsLayer 块内读取 = 帧内只更新图层, 零重组 (传送带高性能路径);
 * 相位 = (t + index/count) % 1 — 出左下立即从右上重现。
 */
@Composable
private fun OrbitItem(
    tState: androidx.compose.runtime.State<Float>,
    phaseOffset: Float,
    arcA: Offset2,
    arcC: Offset2,
    arcB: Offset2,
    selected: Boolean,
    halfIcon: Float,
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val scale = if (selected) 1.15f else 1f
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .graphicsLayer {
                val phase = (tState.value + phaseOffset) % 1f
                val p = quadPoint(arcA, arcC, arcB, phase)
                translationX = p.x - size.width / 2f
                translationY = p.y - halfIcon
            },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(ICON_DP)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                .then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    } else Modifier
                )
                .clickable(onClick = onClick),
        ) {
            icon()
        }
        if (selected) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** 助手头像圆 (图/表情/首字)。 */
@Composable
private fun AssistantCircle(avatar: Avatar, name: String) {
    when (avatar) {
        is Avatar.Image -> {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(avatar.url).build(),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().padding(2.dp)
                    .graphicsLayer { clip = true; shape = CircleShape },
            )
        }
        is Avatar.Emoji -> {
            Text(text = avatar.content, style = MaterialTheme.typography.titleMedium)
        }
        else -> {
            Text(
                text = name.take(1).ifBlank { "?" },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
