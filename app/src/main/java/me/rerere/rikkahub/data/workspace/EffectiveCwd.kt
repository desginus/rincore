/* 【域 D·工作区沙箱】 — CWD 生效链锚点 | 地图: docs/APP_MAP.md §D */
package me.rerere.rikkahub.data.workspace

/* ───【自研】EffectiveCwd.kt — v4.8.189 "CWD 生效链"单一锚点 (L-A4)
 * 背景: `folder.cwd ?: assistant.workspaceCwd` 这一条优先级规则此前在四处各自重写
 *  (FilesPicker / ChatPage / ChatDrawerVM / ChatService, 且 ChatVM 另有一层包查询) ——
 *  同一规则五种写法, 行为微差不可查。本文件收口为唯一权威 (v4.8.24 定版语义):
 *    项目包 CWD 优先 (项目包锚定) → 否则回退 fallback (通常=助手 workspaceCwd;
 *    fork 提取为助手场景 = 源助手 workspaceCwd)。
 * 一切 CWD 优先级判定必须经本文件, 别处不得自写 `?:` 链。
 * 全链: ChatService.generate (运行时生效值·消费) / ChatVM.conversationFolderCwd (页面流·消费) /
 *       FilesPicker(选择器显示·消费) / ChatDrawerVM.extractFolderAsAssistant(fork 继承·消费)。
 * ───────────────────────────────────────────────────────────────*/

import me.rerere.rikkahub.data.repository.FolderRepository
import kotlin.uuid.Uuid

object EffectiveCwd {

    /** 纯函数: 项目包 cwd 优先, 回退兜底 — 全库唯一一处优先级判定。 */
    fun pick(folderCwd: String?, fallbackCwd: String?): String? = folderCwd ?: fallbackCwd

    /** 由 folderId 解析包 cwd (包不存在/查询异常 → null)。 */
    suspend fun fromFolderId(folderRepository: FolderRepository, folderId: Uuid?): String? =
        runCatching { folderId?.let { fid -> folderRepository.getFolderById(fid)?.cwd } }.getOrNull()

    /** 对话场景完整解析: folderId → 包 cwd 优先, 否则 fallback。 */
    suspend fun resolve(
        folderRepository: FolderRepository,
        folderId: Uuid?,
        fallbackCwd: String?,
    ): String? = pick(fromFolderId(folderRepository, folderId), fallbackCwd)
}
