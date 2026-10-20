package me.rerere.rikkahub.data.workspace

import android.content.Context
import java.io.File

/**
 * v5.0.32 用户定案：工作区「物理锁定」。
 *
 * 设计原则（用户原话：把桥断了，桥的内容全部保存继承，但不再和软件本体产生关系）：
 *  - **数据零改动**：锁 = 一个标记文件，绝不删除、不移动工作区任何字节；
 *  - **桥断开**：锁定时不启动 SandboxBridgeServer、不预热 rootfs、不读写 workspaces；
 *  - **可逆**：用户手动解锁后一切恢复；
 *  - **桥的内容保留**：`files/.rin-bridge/`（token）与 `files/workspaces/` 原样留着。
 *
 * 锁定文件：`files/.workspace_locked`
 * 反向标记：`files/.workspace_unlocked`（用户手动解锁过，则永不自动再锁）
 */
object WorkspaceLock {

    private const val LOCK_FILE = ".workspace_locked"
    private const val UNLOCK_FILE = ".workspace_unlocked"

    fun lockFile(context: Context): File = File(context.filesDir, LOCK_FILE)
    fun unlockFile(context: Context): File = File(context.filesDir, UNLOCK_FILE)

    /** 是否处于锁定态（默认未锁） */
    fun isLocked(context: Context): Boolean {
        val f = lockFile(context)
        return f.exists() && !unlockFile(context).exists()
    }

    /** 加锁（只写标记，不动数据） */
    fun lock(context: Context) {
        runCatching { lockFile(context).writeText("locked") }
    }

    /** 解锁（写反向标记，并移除锁文件） */
    fun unlock(context: Context) {
        runCatching { unlockFile(context).writeText("unlocked") }
        runCatching { lockFile(context).delete() }
    }
}
