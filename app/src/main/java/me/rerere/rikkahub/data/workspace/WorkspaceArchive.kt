package me.rerere.rikkahub.data.workspace

import android.content.Context
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
     * v5.0.31 用户定案：把 zip 解压到**指定的新工作区目录**（导入工作区）。
     *
     * zip 结构：`workspaces/<源id>/<工作区根下的相对路径>`。
     * 导入时剥掉 `workspaces/<源id>/` 前缀，内容落到 `workspaces/<目标id>/` 下，
     * 从而与 `WorkspaceRepository.create()` 新建的目录结构完全一致（DB 记录由调用方创建）。
     *
     * 安全：目标目录已存在且有内容 → 先整目录改名备份（`<id>.import-backup-<ts>`），
     * 失败回滚；备份永不自动删除。
     */
    suspend fun extractInto(context: Context, targetWorkspaceId: String, input: java.io.InputStream): Result =
        withContext(Dispatchers.IO) {
            val root = File(workspacesRoot(context), targetWorkspaceId)
            val backup = File(workspacesRoot(context), "$targetWorkspaceId.import-backup-${System.currentTimeMillis()}")
            var movedAway = false
            if (root.exists() && root.listFiles()?.isNotEmpty() == true) {
                if (!root.renameTo(backup)) error("无法备份目标工作区目录，已中止导入")
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
                        if (!entry.isDirectory && name.startsWith("$ROOT_DIR/") && !name.contains("..")) {
                            // 剥掉 workspaces/<源id>/
                            val afterRoot = name.removePrefix("$ROOT_DIR/")
                            val rest = afterRoot.substringAfter('/', "")
                            // v5.0.32：只接受 files/ 下的内容（旧 zip 里的 linux/、temp/ 一律跳过）
                            if (!rest.startsWith("files/")) {
                                zis.closeEntry()
                                entry = zis.nextEntry
                                continue
                            }
                            val rel = rest
                            if (rel.isNotBlank()) {
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
                runCatching { root.deleteRecursively() }
                if (movedAway) runCatching { backup.renameTo(root) }
                throw t
            }
        }

    /**
     * v5.0.30 用户定案：单个工作区导出（入口 = 工作区列表 → 重命名/删除之间的「导出工作区」）。
     * 只读源目录，不写不删；zip 内路径统一为 `workspaces/<workspaceId>/...`，与整包导出同构。
     */
    suspend fun exportOne(context: Context, workspaceId: String, output: java.io.OutputStream): Result =
        withContext(Dispatchers.IO) {
            val workspaceRoot = File(workspacesRoot(context), workspaceId)
            if (!workspaceRoot.exists()) return@withContext Result(0, 0)
            // v5.0.32 用户实证修复：只导出 files/（用户文件）；
            // 排除 linux/（rootfs，GB 级，可重建）与 temp/（临时垃圾）。
            val userFiles = File(workspaceRoot, "files")
            if (!userFiles.exists()) return@withContext Result(0, 0)
            var count = 0
            var bytes = 0L
            ZipOutputStream(output.buffered()).use { zos ->
                userFiles.walkTopDown()
                    .onEnter { dir ->
                        // 跳过符号链接目录（rootfs 里的 /bin→/usr/bin 会导致重复打包爆炸）
                        !java.nio.file.Files.isSymbolicLink(dir.toPath())
                    }
                    .forEach { f ->
                        if (!f.isFile) return@forEach
                        if (java.nio.file.Files.isSymbolicLink(f.toPath())) return@forEach
                        val rel = f.relativeTo(userFiles).path.replace(File.separatorChar, '/')
                        zos.putNextEntry(ZipEntry("$ROOT_DIR/$workspaceId/files/$rel"))
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
