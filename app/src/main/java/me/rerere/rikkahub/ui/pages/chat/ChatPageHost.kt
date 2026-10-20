/* 【域 A·对话核心】 | 地图: docs/APP_MAP.md §A */
package me.rerere.rikkahub.ui.pages.chat

/* ─── 功能外挂层（v5.0.16）───────────────────────────────────────────────
 * 用户定版：「原版负责模糊模块的文件整件照搬、一个字不要改；功能上的东西
 * 做成外挂形式，不要动里面的逻辑。」
 * → ChatPage.kt / ChatList.kt / ChatInput.kt / ChatInputHaze.kt /
 *   Background.kt / MeshGradientBackground.kt / FilesPicker.kt 全部=上游
 *   3.0.0 逐字（模糊链零我方代码）。
 * → 我方功能以本文件（外挂层）包在原版页面之外：Box { 原版 ChatPage; 外挂 }。
 *   不修改原版文件任何一行。
 * ─────────────────────────────────────────────────────────────────────── */

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

/**
 * 对话页入口（外挂层）：原版 ChatPage 逐字渲染 + 我方功能浮层。
 *
 * 当前外挂件：
 *  - 双星轨选择器（EmptyOrbitSelector）：空对话时浮于原版页面之上。
 * 设计约束：本文件不得引用原版页面内部符号；只通过 ChatVM 公开接口读写。
 */
@Composable
fun ChatPageHost(id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid? = null) {
    // 与 ChatPage 内部完全相同的创建参数 → Koin 返回同一实例（同一 route 作用域）
    val vm: ChatVM = koinViewModel(parameters = { parametersOf(id.toString()) })
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val setting by vm.settings.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        // ── 原版页面（逐字，零修改） ──
        ChatPage(id = id, text = text, files = files, nodeId = nodeId)

        // ── v5.0.18 紧急撤除：双星轨浮层 ──────────────────────────────
        // 事故（用户 2026-10-20）：该浮层作为 Box 第二层、fillMaxSize 画在
        // ChatPage 之上，而抽屉（ModalNavigationDrawer）在 ChatPage 内部
        // → 抽屉拉开后被浮层整片覆盖，空对话时铺满页面，用户完全无法操作。
        // 结论：外挂层不得承载"全屏交互浮层"——它永远在页面内部层（抽屉/
        // 输入栏/标题栏）之上。
        // 正确回归方式（下版）：把双星轨挂到聊天页的"空态内容区"（与消息
        // 列表同级、抽屉之下），或由 ChatPage 提供空态插槽后再接入。
        // 本体保留在 EmptyOrbitSelector.kt，未删除。
    }
}
