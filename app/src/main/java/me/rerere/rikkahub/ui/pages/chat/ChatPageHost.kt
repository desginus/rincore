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

        // ── 外挂：双星轨（空对话才浮出） ──
        if (conversation.messageNodes.isEmpty()) {
            EmptyOrbitSelector(
                currentFolderId = conversation.folderId,
                onSelectAssistant = { assistantId ->
                    // 全局切助手 + 内存态同步归属（与 v4.8.148 定版一致）
                    vm.updateSettings(setting.copy(assistantId = assistantId))
                    vm.updateConversation(
                        conversation.copy(assistantId = assistantId, folderId = null)
                    )
                    vm.saveConversationAsync()
                },
                onSelectFolder = { folderId ->
                    // 归属写回（空对话未入库，saveConversationAsync 尽力落盘）
                    vm.updateConversation(conversation.copy(folderId = folderId))
                    vm.saveConversationAsync()
                },
                hazeState = null, // 外挂层无源（原版 hazeState 在页面内部）→ 图标走半透明回退
                blurEnabled = false,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 88.dp, bottom = 88.dp),
            )
        }
    }
}
