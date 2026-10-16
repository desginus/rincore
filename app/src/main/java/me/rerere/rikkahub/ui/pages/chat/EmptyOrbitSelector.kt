/* 【域 A·对话核心】 | 地图: docs/APP_MAP.md §A */
package me.rerere.rikkahub.ui.pages.chat

/* ───【自研】EmptyOrbitSelector.kt — v4.8.148 (用户定版 v147 精修)
 * 新聊天/默认页(无消息)双弧轨道选择器:
 *   · 外弧轨道 = 助手 (柔光玻璃圆 + 头像/统一图标) — 点选 = 直切全局助手+会话归属同步
 *   · 内弧轨道 = 项目包 (当前助手的包) — 点选 = 回写本对话 folderId
 *   · 几何: 右上(新增对话按钮下方) → 左下(输入栏左上方), 缓弧 (不可见, 不画导轨线);
 *     内弧 = 外弧沿弧法线内缩紧贴。
 *   · 动画: 传送带轮播 (%1 环绕, 出左下立即右上重现) + 出入场淡入淡出 (相位端 8% 窗)。
 *   · 图标: 与输入框同族柔光玻璃圆; 头像=真图/表情, 未设=统一代表性图标 (AiMagic)。
 *   · 状态: 每个图标下常驻名称备注; 当前项 = 放大 + 主色描边。
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import dev.chrisbanes.haze.HazeState
import me.rerere.rikkahub.ui.components.motion.rinGlassHighlight
import me.rerere.rikkahub.ui.components.motion.rinGlass
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.rikkahub.R
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
    onSelectFolder: (Uuid?) -> Unit,
    hazeState: HazeState? = null,
    blurEnabled: Boolean = true,
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

        // v4.8.148: 导轨线已按用户定版抹除 (轨道本身不可见, 仅图标队列可见)

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
                hazeState = hazeState,
                blurEnabled = blurEnabled,
                onClick = { onSelectAssistant(assistant.id) },
            ) {
                AssistantCircle(avatar = assistant.avatar, name = assistant.name, highlight = selected)
            }
        }

        // 内弧: 「聊天」默认项 + 项目包 (当前助手)
        // v4.8.151 (用户: 项目包选择项里应当有聊天这个默认项): 内弧第一位 = 「聊天」
        // (未归类默认空间, folderId=null), 与抽屉项目包列表同序 (聊天在前)。
        val m = folders.size
        val innerCount = m + 1
        OrbitItem(
            tState = tState,
            phaseOffset = 0f,
            arcA = ai,
            arcC = ci,
            arcB = bi,
            selected = currentFolderId == null,
            halfIcon = iconPx / 2f,
            label = stringResource(R.string.chat_page_folder_default),
            hazeState = hazeState,
            blurEnabled = blurEnabled,
            onClick = { onSelectFolder(null) },
        ) {
            Box(
                modifier = Modifier.size(ICON_DP),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = HugeIcons.Message02,
                    contentDescription = null,
                    // v4.8.152: 选中 = 图标本体发亮 (主色)
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        folders.forEachIndexed { index, folder ->
            val selected = folder.id == currentFolderId
            OrbitItem(
                tState = tState,
                phaseOffset = if (innerCount > 0) (index + 1).toFloat() / innerCount else 0f,
                arcA = ai,
                arcC = ci,
                arcB = bi,
                selected = selected,
                halfIcon = iconPx / 2f,
                label = folder.name,
                hazeState = hazeState,
                blurEnabled = blurEnabled,
                onClick = { onSelectFolder(folder.id) },
            ) {
                Box(
                    modifier = Modifier.size(ICON_DP),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = HugeIcons.Folder01,
                        contentDescription = null,
                        // v4.8.152: 选中 = 图标本体发亮 (主色)
                        tint = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

private data class Offset2(val x: Float, val y: Float)

/** v4.8.151: smoothstep 淡入淡出缓动 (0..1 → 平滑 S 曲线)。 */
private fun smoothFade(x: Float): Float {
    val s = x.coerceIn(0f, 1f)
    return s * s * (3f - 2f * s)
}

/** 二次贝塞尔取样 (与 Canvas 轨迹同一条曲线)。 */
private fun quadPoint(a: Offset2, c: Offset2, b: Offset2, t: Float): Offset2 {
    val u = 1f - t
    return Offset2(
        u * u * a.x + 2f * u * t * c.x + t * t * b.x,
        u * u * a.y + 2f * u * t * c.y + t * t * b.y,
    )
}

/**
 * 轨道项: 柔光玻璃圆 (与输入框同族) + 常驻名称备注。
 * 动画值 (tState) 在 graphicsLayer 块内读取 = 帧内只更新图层, 零重组 (传送带高性能路径);
 * 相位 = (t + index/count) % 1 — 出左下立即从右上重现; 两端 8% 窗淡入淡出 (入出场平滑)。
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
    hazeState: HazeState?,
    blurEnabled: Boolean,
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
                // v4.8.151 (用户: 高亮环在淡入淡出时生硬残缺): 端窗收窄 5% + smoothstep 缓动
                // (起步/收尾平滑, 消灭线性淡出的"半透明残缺环"), 且端部整体轻微缩入
                // (0.85→1) — 退场读作"缩入角落", 与平移/淡出合成为柔和出入场。
                val k = smoothFade(kotlin.math.min(phase / 0.05f, (1f - phase) / 0.05f))
                alpha = k
                scaleX = 0.85f + 0.15f * k
                scaleY = 0.85f + 0.15f * k
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                    pivotFractionX = 0.5f,
                    pivotFractionY = if (size.height > 0f) (halfIcon / size.height).coerceIn(0f, 1f) else 0.5f,
                )
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
                // v4.8.152 (用户定版方案): 选中态 = **图标本体发亮** (主色 glyph + 主色柔光晕),
                // 不再套描边亮环 — 环在出入场淡出时会呈"残缺"观感, 从结构上移除。
                .then(
                    if (selected) {
                        Modifier.shadow(
                            elevation = 10.dp,
                            shape = CircleShape,
                            ambientColor = MaterialTheme.colorScheme.primary,
                            spotColor = MaterialTheme.colorScheme.primary,
                        )
                    } else Modifier
                )
                // v4.8.148 (用户定版): 圆形图标 = 输入框同族柔光玻璃
                .rinGlass(hazeState = hazeState, blurEnabled = blurEnabled, shape = CircleShape)
                .rinGlassHighlight(CircleShape)
                .clickable(onClick = onClick),
        ) {
            icon()
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * 助手圆内容 (v4.8.148 用户定版):
 *   真图头像 → 原样展示 (与 UIAvatar 同款直载); 表情头像 → 表情;
 *   未设头像 → **统一代表性图标** (AiMagic = 助手/AI 语义; 不再用文字缩写)。
 */
@Composable
private fun AssistantCircle(avatar: Avatar, name: String, highlight: Boolean = false) {
    when (avatar) {
        is Avatar.Image -> {
            AsyncImage(
                model = avatar.url,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(2.dp)
                    .graphicsLayer {
                        clip = true
                        shape = CircleShape
                    },
            )
        }
        is Avatar.Emoji -> {
            Text(text = avatar.content, style = MaterialTheme.typography.titleMedium)
        }
        else -> {
            Icon(
                imageVector = HugeIcons.AiMagic,
                contentDescription = name,
                // v4.8.152: 选中 = 图标本体发亮 (主色)
                tint = if (highlight) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
