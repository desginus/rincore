package me.rerere.rikkahub.ui.components.message

/* ───【自研】SmoothStreamingText.kt — v3.10.9 流式输出平滑整形
 * 需求: 服务端块到达 → 用户看到"一节一节往出窜"; 要求逐字符平滑输出,
 *       速度动态对齐服务端真实速度 (忽快忽慢时我们的速度呈平滑曲线),
 *       且不增加首字延迟。
 *
 * 机制:
 * 1. 观察 target 文本增量: 按 (新字符数 / 到达间隔) 估算服务端瞬时速率,
 *    经 EMA 平滑为 rateTarget (目标速度)
 * 2. 输出循环 33ms/拍: 每拍输出 rateCurrent/30 字符 (30fps 视觉连续),
 *    rateCurrent 以 0.85/0.15 向 rateTarget 渐变 — 速度变化本身平滑
 * 3. 首字零延迟: 新内容首帧立即显示一个字符再进入节奏
 * 4. 历史消息/回退文本: displayedLen 初始=target.length 直接显示全文,
 *    变短/重写时同样直接显示 (不重放节奏)
 * 5. 完成自适应: 无新内容追平后自然结束; 无需生成完成信号
 * ───────────────────────────────────────────────────────────────*/
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock

// 输出节拍: 33ms ≈ 30fps, 视觉连续且重组可控
private const val TICK_MS = 33L
// 速率上下限 (字符/秒): 下限防卡死观感, 上限防突变抖动
private const val MIN_CPS = 8f
private const val MAX_CPS = 400f

@Composable
fun SmoothStreamingText(
    target: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    onClickCitation: (String) -> Unit = {},
    // v3.10.9: 生成中闸门 — true=流式平滑; false=直接全文显示
    // (历史消息/消息完成/编辑/版本切换不得重放节奏)
    loading: Boolean = false,
) {
    // 非生成中: 直接走 MarkdownBlock 原生路由 (含 v3.10.8 新内核特征路由),
    // 平滑状态机不参与 — 零影响
    if (!loading) {
        MarkdownBlock(
            content = target,
            modifier = modifier,
            style = style,
            onClickCitation = onClickCitation,
        )
        return
    }
    // 已平滑输出的字符数 — 初始 = 全文 (历史消息直接显示, 零影响)
    var displayedLen by remember { mutableIntStateOf(target.length) }
    // 输出速率 (当前, 平滑逼近 rateTarget)
    var rateCurrent by remember { mutableFloatStateOf(60f) }
    // 服务端速率 EMA (目标速度)
    var rateTarget by remember { mutableFloatStateOf(60f) }
    // 上次观察到的长度与时间
    var lastLen by remember { mutableIntStateOf(target.length) }
    var lastUpdateMs by remember { mutableLongStateOf(0L) }
    var smoothing by remember { mutableStateOf(false) }

    // 观察器: 估算服务端速率, 首字立即输出, 启动输出循环
    LaunchedEffect(target) {
        val t = target.length
        val now = System.currentTimeMillis()
        if (t > lastLen) {
            // 服务端新增字符的瞬时速率 (字符/秒)
            val instant = if (lastUpdateMs == 0L) {
                MAX_CPS
            } else {
                val dt = (now - lastUpdateMs).coerceAtLeast(1L)
                (t - lastLen) * 1000f / dt
            }.coerceIn(MIN_CPS, MAX_CPS)
            // EMA 平滑目标速度 — 服务端快/慢的突变被吸收为平滑目标
            rateTarget = if (lastUpdateMs == 0L) instant else rateTarget * 0.7f + instant * 0.3f
            lastLen = t
            lastUpdateMs = now
            if (!smoothing) {
                // 首字零延迟: 立即显示第一个新字符
                displayedLen = (displayedLen + 1).coerceAtMost(t)
                smoothing = true
            }
        } else if (t < lastLen) {
            // 文本回退/重写 — 直接显示全文, 重新开始节奏
            displayedLen = t
            lastLen = t
            lastUpdateMs = now
        }
    }

    // 输出循环: 33ms/拍, 速率渐变逼近服务端速度
    LaunchedEffect(smoothing) {
        if (!smoothing) return@LaunchedEffect
        while (true) {
            val remain = target.length - displayedLen
            if (remain <= 0) {
                smoothing = false
                break
            }
            // 输出速率向服务端速率渐变 (0.85/0.15 一阶低通 — 速度变化呈平滑曲线)
            rateCurrent = rateCurrent * 0.85f + rateTarget * 0.15f
            val perTick = (rateCurrent / (1000f / TICK_MS)).toInt().coerceAtLeast(1)
            displayedLen = minOf(target.length, displayedLen + perTick)
            delay(TICK_MS)
        }
    }

    // 平滑期间走 MarkdownBlock (旧内核, snapshotFlow 异步解析不卡主线程) —
    // 新内核 (mikepenz) 同步解析, 30Hz 节拍下会卡 UI, 完成后再切换 (见上)
    MarkdownBlock(
        content = target.take(displayedLen),
        modifier = modifier,
        style = style,
        onClickCitation = onClickCitation,
    )
}
