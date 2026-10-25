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

    /**
     * v4.8.149 (用户定版): 本次进程"进入过的项目包"记忆 (按助手)。
     *
     * 背景: 用户规格 — "针对聊天这个默认项目包为空、但其他项目包有实际内容的情况,
     * 如果当前进入了其他项目包, 则默认该项目包内为本次进程的默认页面; 而不是每一次
     * 选择/重开抽屉都是项目包选择页面"。进入 = 抽屉点选 或 空态轨道内弧选包
     * (两者同走 ChatDrawerVM.selectFolder 单入口)。
     * 写入方: ChatDrawerVM; 读取方: ChatDrawerVM.runLandingCheck (落地默认页)。
     */
    val enteredFolderIdByAssistant = java.util.concurrent.ConcurrentHashMap<Uuid, Uuid>()

    fun recordEntered(assistantId: Uuid, folderId: Uuid) {
        enteredFolderIdByAssistant[assistantId] = folderId
    }

    /**
     * v5.0.97 ★「切到另一个助手发消息，旧项目包的标签还在、消息还归到旧包」根修（一）：
     * 记录每个项目包属于哪个助手 —— 取用时据此校验，跨助手的残留选区一律不采用。
     */
    private val folderOwner = java.util.concurrent.ConcurrentHashMap<Uuid, Uuid>()

    fun recordFolderOwner(folderId: Uuid, assistantId: Uuid) {
        folderOwner[folderId] = assistantId
    }

    /** 未知归属时返回 null（调用方按"不阻断"处理，避免误伤正常路径）。 */
    fun ownerOf(folderId: Uuid): Uuid? = folderOwner[folderId]

    /** v5.0.97 ★切助手时清空当前选区（否则新助手会沿用上一个助手的项目包）。 */
    fun clearSelection() {
        selectedFolderId.value = null
    }
}
