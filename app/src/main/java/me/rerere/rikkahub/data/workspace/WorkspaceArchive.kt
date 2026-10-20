package me.rerere.rikkahub.data.workspace

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * v5.0.28 用户定案：工作区整包导出/导入。
 *
 * 背景：数据备份**不包含** `filesDir/workspaces/`（设计如此，体积大）。
 * 换包名/换设备/重装时工作区数据会丢——这是用户唯一无法自动继承的部分。
 *
 * 本工具提供：
 *  - 导出：把 `filesDir/workspaces/` 打成一个 zip（保留相对路径），写到调用方指定的输出流；
 *  - 导入：从 zip 解压回 `filesDir/workspaces/`（覆盖同名，保留未涉及的内容）。
 *
 * 目录结构：workspaces/<workspaceId>/...（rootfs + files）
 */
object WorkspaceArchive {

    private const val ROOT_DIR = "workspaces"

    data class Result(val files: Int, val bytes: Long)

    fun workspacesRoot(context: Context): File = File(context.filesDir, ROOT_DIR)

    /** 导出到输出流（调用方负责 CreateDocument / 文件选择器）。 */
    suspend fun exportTo(context: Context, output: java.io.OutputStream): Result =
        withContext(Dispatchers.IO) {
            val root = workspacesRoot(context)
            var count = 0
            var bytes = 0L
            ZipOutputStream(output.buffered()).use { zos ->
                if (!root.exists()) return@withContext Result(0, 0)
                root.walkTopDown().forEach { f ->
                    if (!f.isFile) return@forEach
                    val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                    zos.putNextEntry(ZipEntry("$ROOT_DIR/$rel"))
                    FileInputStream(f).use { input -> input.copyTo(zos) }
                    zos.closeEntry()
                    count++
                    bytes += f.length()
                }
            }
            Result(count, bytes)
        }

    /**
     * v5.0.30 用户定案：单个工作区导出（入口 = 工作区列表 → 重命名/删除之间的「导出工作区」）。
     * 只读源目录，不写不删；zip 内路径统一为 `workspaces/<workspaceId>/...`，与整包导出同构。
     */
    suspend fun exportOne(context: Context, workspaceId: String, output: java.io.OutputStream): Result =
        withContext(Dispatchers.IO) {
            val root = File(workspacesRoot(context), workspaceId)
            if (!root.exists()) return@withContext Result(0, 0)
            var count = 0
            var bytes = 0L
            ZipOutputStream(output.buffered()).use { zos ->
                root.walkTopDown().forEach { f ->
                    if (!f.isFile) return@forEach
                    val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                    zos.putNextEntry(ZipEntry("$ROOT_DIR/$workspaceId/$rel"))
                    FileInputStream(f).use { input -> input.copyTo(zos) }
                    zos.closeEntry()
                    count++
                    bytes += f.length()
                }
            }
            Result(count, bytes)
        }

    /**
     * 从输入流导入（覆盖同名文件）。
     *
     * v5.0.28 用户定案「首先保证工作区数据安全」：
     *  1. 解压前把现有 workspaces 整目录**改名备份**（不删任何字节）；
     *  2. 解压失败 → 立即回滚（删除半成品，把备份改名回来）；
     *  3. 解压成功 → 备份目录保留为 `workspaces.backup-<时间戳>`，由用户自行清理（本工具永不删除备份）。
     */
    suspend fun importFrom(context: Context, input: java.io.InputStream): Result =
        withContext(Dispatchers.IO) {
            val root = workspacesRoot(context)
            val backup = File(root.parentFile, "workspaces.backup-${System.currentTimeMillis()}")
            var movedAway = false
            if (root.exists() && root.listFiles()?.isNotEmpty() == true) {
                // 整目录改名（原子操作，失败则中止导入，绝不动原数据）
                if (!root.renameTo(backup)) {
                    error("无法备份现有工作区（改名失败），出于安全已中止导入")
                }
                movedAway = true
            }
            var count = 0
            var bytes = 0L
            try {
                root.mkdirs()
                ZipInputStream(input.buffered()).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name.replace('\\', '/')
                        // 只接受 workspaces/ 前缀，拒绝路径穿越
                        if (name.startsWith("$ROOT_DIR/") && !name.contains("..")) {
                            val rel = name.removePrefix("$ROOT_DIR/")
                            if (rel.isNotBlank() && !entry.isDirectory) {
                                val target = File(root, rel)
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { out -> out.write(zis.readBytes()) }
                                count++
                                bytes += target.length()
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                Result(count, bytes)
            } catch (t: Throwable) {
                // 失败回滚：清掉半成品，把备份改回原名
                runCatching { root.deleteRecursively() }
                if (movedAway) runCatching { backup.renameTo(root) }
                throw t
            }
        }
}
