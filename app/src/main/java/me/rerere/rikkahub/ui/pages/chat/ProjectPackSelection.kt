/* 【域 A·对话核心】 | 地图: docs/APP_MAP.md §A */
package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.uuid.Uuid

/**
 * 4.8.27: 项目包选区 — 全局内存状态 (跨 VM 只读访问)。
 *
 * 背景: "空对话归属跟随选区"需要发送时 (ChatVM) 读取抽屉选区 (ChatDrawerVM),
 * 但两者是独立 VM。此前 v4.8.26 用 ChatPage LaunchedEffect 在"对话打开时"
 * 同步归属, 存在致命时序缺陷 — session 加载窗口期 (state 初始值 = 空对话):
 *   ① 误判"空对话"触发同步 → updateConversationState 抢先写入 →
 *      session.initialized=true → DB 加载被丢弃 (对话永远显示为空);
 *   ② 归属被误改 (对话"跳包")。
 *
 * 修复 (v4.8.27): 归属同步移至发送时 (对话必已加载完成, 判定可靠);
 * 选区经本单例跨 VM 传递 (写入方 ChatDrawerVM; 读取方 ChatVM.handleMessageSend)。
 */
object ProjectPackSelection {
    val selectedFolderId = MutableStateFlow<Uuid?>(null)
}
