package me.rerere.rikkahub.data.repository


/* ───【原版对齐】WorkspaceRepository | 差异 +113 行
 * 来源: 原版移植 + 自研 (工作区仓库增强)
 * 差异: launchProcess 常驻 (v3.5.27)、refreshDshSkillRoots、
 *       getAllWorkspaces、工具审批等自研
 * ───────────────────────────────────────────────────────────────*/
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.uuid.Uuid

class WorkspaceRepository(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val settingsStore: SettingsStore,
    private val context: Context,
) {
    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    /**
     * v3.6.85: DeepSeek Harness 插件生态兼容 — 刷新 DSH 技能根。
     * 扫描所有 workspace files 区的 .dsh/skills 与 .agents/skills
     * (DSH 官方技能发现根), 注入 SkillManager 作为只读技能源。
     */
    suspend fun refreshDshSkillRoots(skillManager: me.rerere.rikkahub.data.files.SkillManager) = withContext(Dispatchers.IO) {
        runCatching {
            val dshRoots = dao.getAll().flatMap { ws ->
                listOf(
                    File(manager.filesDir(ws.root), ".dsh/skills"),
                    File(manager.filesDir(ws.root), ".agents/skills"),
                )
            }
            // 仅更新 dsh__ 前缀根, 保留 plugin__ 根 (PluginManager 注入)
            val others = skillManager.extraRootsSnapshot().filter { it.prefix != "dsh__" }
            skillManager.setExtraSkillRoots(
                others + dshRoots.map { me.rerere.rikkahub.data.files.SkillManager.ExtraSkillRoot("dsh__", it) }
            )
        }.onFailure {
            Log.w(TAG, "refreshDshSkillRoots failed: ${it.message}")
        }
    }

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        val workspaces = dao.getAll()
        for (workspace in workspaces) {
            val dir = manager.workspaceDir(workspace.root)
            if (!dir.exists()) {
                // 目录缺失时不删除记录(例如恢复备份后工作区文件未随数据库一起恢复),
                // 仅标记为 BROKEN 以保留记录与助手绑定, 避免误删用户工作区
                Log.w(TAG, "Workspace directory missing, marking as broken: id=${workspace.id}, root=${workspace.root}")
                if (workspace.shellStatus != WorkspaceShellStatus.BROKEN.name) {
                    updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
                }
                continue
            }
            val statusName = workspace.shellStatus
            if ((statusName == WorkspaceShellStatus.READY.name || statusName == WorkspaceShellStatus.INSTALLING.name)
                && !manager.hasRootfs(workspace.root)
            ) {
                Log.w(TAG, "Rootfs missing, resetting shell status: id=${workspace.id}")
                updateShellState(workspace.id, WorkspaceShellStatus.DISABLED.name)
            }
        }
        // v4.5.24: 每次启动幂等注入内置工具 (新装 / 工具升级自动就位)
        provisionBuiltinTools()
    }

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    /**
     * v4.5.24: 内置工具注入 — 把 assets/rin-tools 下的工具写入各工作区 rootfs 的
     * /usr/local/bin (proot PATH 已含该目录), 使沙箱开箱具备 Office 文档手术编辑
     * 能力 (office-edit: 只改被触及的 XML 部件, 其余字节原样保留, 格式不崩)。
     * 幂等: 内容一致跳过; 工具随版本升级在下次启动/安装完成时自动覆盖。
     */
    suspend fun provisionBuiltinTools(workspaceId: String? = null) = withContext(Dispatchers.IO) {
        val workspaces = if (workspaceId != null) listOfNotNull(dao.getById(workspaceId)) else dao.getAll()
        for (workspace in workspaces) {
            if (workspace.shellStatus != WorkspaceShellStatus.READY.name) continue
            if (!manager.hasRootfs(workspace.root)) continue
            runCatching { provisionInto(manager.linuxDir(workspace.root)) }
                .onFailure { Log.w(TAG, "provisionBuiltinTools failed: id=${workspace.id}", it) }
        }
    }

    private fun provisionInto(linuxDir: File) {
        val names = runCatching { context.assets.list(TOOLS_ASSET_DIR)?.toList().orEmpty() }
            .getOrDefault(emptyList())
        if (names.isEmpty()) return
        val binDir = File(linuxDir, "usr/local/bin").apply { mkdirs() }
        for (name in names) {
            val target = File(binDir, name)
            val bytes = context.assets.open("$TOOLS_ASSET_DIR/$name").use { it.readBytes() }
            val unchanged = target.isFile && target.length() == bytes.size.toLong() &&
                runCatching { target.readBytes().contentEquals(bytes) }.getOrDefault(false)
            if (!unchanged) {
                target.writeBytes(bytes)
                Log.i(TAG, "provisioned builtin tool: ${target.absolutePath} (${bytes.size} bytes)")
            }
            target.setExecutable(true, false)
            target.setReadable(true, false)
        }
    }


    /** 全量 workspace 列表 (插件扫描等) */
    suspend fun getAllWorkspaces(): List<WorkspaceEntity> = dao.getAll()

    suspend fun create(name: String): WorkspaceEntity {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val finalName = name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = null)) {
            "Workspace name already exists: $finalName"
        }
        val workspace = WorkspaceEntity(
            id = id,
            name = finalName,
            root = id,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
        )
        manager.ensureWorkspace(workspace.root)
        dao.upsert(workspace)
        return workspace
    }

    suspend fun rename(id: String, name: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        dao.upsert(
            workspace.copy(
                name = finalName,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    /** 名字是否已被其他 workspace 占用（trim 后精确匹配，排除 [excludeId] 自身） */
    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return dao.getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    suspend fun setShellCompatibilityMode(id: String, enabled: Boolean) {
        dao.setShellCompatibilityMode(id, enabled, System.currentTimeMillis())
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean {
        val workspace = dao.getById(id) ?: return false
        val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
        dao.upsert(
            workspace.copy(
                toolApprovals = JsonInstant.encodeToString(overrides),
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    suspend fun installRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean {
        val workspace = dao.getById(id) ?: return false
        updateShellState(workspace, WorkspaceShellStatus.INSTALLING.name)
        try {
            // runInterruptible 让协程取消转成线程中断, 打断 install 内阻塞的下载/解压循环
            runInterruptible(Dispatchers.IO) {
                rootfsInstaller.install(workspace.root, url, onProgress)
            }
            updateShellState(workspace, WorkspaceShellStatus.READY.name)
            // v4.5.24: 安装完成即注入内置工具, 开箱可用
            provisionBuiltinTools(workspace.id)
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw e
        } catch (e: InterruptedException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw CancellationException("Rootfs install cancelled").also { it.initCause(e) }
        } catch (e: Throwable) {
            Log.e(TAG, "installRootfs failed: workspace=${workspace.id}, root=${workspace.root}, url=$url", e)
            updateShellState(workspace, WorkspaceShellStatus.BROKEN.name)
            throw e
        }
    }

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        manager.listFiles(workspace.root, path, area)
    }

    suspend fun readText(
        id: String,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.readText(workspace.root, path)
    }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.writeText(workspace.root, path, text, overwrite)
    }

    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.importFile(workspace.root, destinationPath, area, fileName, inputStream)
    }

    suspend fun fileSize(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.fileSize(workspace.root, path, area)
    }

    suspend fun resolveFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.resolveFile(workspace.root, path, area)
    }

    suspend fun exportFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.exportFile(workspace.root, path, area, outputStream)
    }

    /**
     * v4.8.5: 文件夹打包为 zip — 完整导出重写。
     * v4.5.3 版本经 manager.listFiles 递归, 而 listFiles 内部 take(maxListEntries=500)
     * 截断 — 单层超 500 条目即静默丢失 (用户实证: 解压后内容丢失/破损)。
     * 现改走 WorkspaceManager.exportFolderToZip: 直接宿主文件树递归, 全量条目 +
     * mtime 保留 + 空目录/空文件完整; 返回打包文件数。
     */
    suspend fun exportFolderZip(
        id: String,
        area: WorkspaceStorageArea,
        folderPath: String,
        outputStream: OutputStream,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        val stats = manager.exportFolderToZip(
            root = workspace.root,
            folderPath = folderPath,
            area = area,
            outputStream = outputStream,
        )
        stats.files
    }

    /** v4.5.2: 工作区 shell 命令执行 (VM 层文件夹打包等工具用途) */
    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
    ): me.rerere.workspace.WorkspaceCommandResult = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.executeCommand(root = workspace.root, command = command, cwd = cwd)
    }

    /** 按 Rootfs 内绝对路径读取文件大小, 支持 /workspace、bind mount 与 Rootfs 内部路径。
     *  v4.5.27: cwd 非空时 /workspace 解析到助手级子目录 (CWD 专一空间)。 */
    suspend fun rootfsFileSize(
        id: String,
        path: String,
        cwd: String? = null,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.rootfsFileSize(workspace.root, path, cwd)
    }

    /** 按 Rootfs 内绝对路径导出文件内容, 支持 /workspace、bind mount 与 Rootfs 内部路径。
     *  v4.5.27: cwd 非空时 /workspace 解析到助手级子目录 (CWD 专一空间)。 */
    suspend fun exportRootfsFile(
        id: String,
        path: String,
        outputStream: OutputStream,
        cwd: String? = null,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.exportRootfsFile(workspace.root, path, outputStream, cwd)
    }

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): Boolean {
        val deleted = withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext false
            manager.deleteFile(workspace.root, path, recursive, area)
        }
        return deleted
    }

    suspend fun moveFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.moveFile(workspace.root, source, target, overwrite)
    }

    suspend fun renameFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        newName: String,
    ): WorkspaceFileEntry {
        val sourcePath = if (path.startsWith("/")) path else "/$path"
        val parent = sourcePath.substringBeforeLast('/', "")
        val targetPath = if (parent.isEmpty()) "/$newName" else "$parent/$newName"
        return moveFile(id = id, source = sourcePath, target = targetPath, overwrite = false)
    }

    suspend fun createFolder(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        name: String,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        val areaRoot = when (area) {
            WorkspaceStorageArea.FILES -> manager.filesDir(workspace.root)
            WorkspaceStorageArea.LINUX -> manager.linuxDir(workspace.root)
        }
        val resolvedPath = if (path.isBlank()) name else "$path/$name"
        val dir = java.io.File(areaRoot, resolvedPath)
        if (!dir.mkdirs() && !dir.isDirectory) error("无法创建文件夹: $name")
    }

    suspend fun createFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        name: String,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        val areaRoot = when (area) {
            WorkspaceStorageArea.FILES -> manager.filesDir(workspace.root)
            WorkspaceStorageArea.LINUX -> manager.linuxDir(workspace.root)
        }
        val resolvedPath = if (path.isBlank()) name else "$path/$name"
        val file = java.io.File(areaRoot, resolvedPath)
        file.parentFile?.mkdirs()
        if (!file.exists()) file.createNewFile()
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        // runInterruptible 让协程取消转化为线程中断，从而打断阻塞的 Process.waitFor 并杀掉进程
        return runInterruptible(Dispatchers.IO) {
            manager.ensureWorkspace(workspace.root)
            manager.executeCommand(
                workspace.root, command, cwd, timeoutMillis, stdin,
                shellCompatibilityMode = workspace.shellCompatibilityMode,
            )
        }
    }

    /**
     * 启动常驻进程 (不等待) — MCP stdio 桥接: 在 workspace 沙箱内启动
     * Python/Node MCP 服务器, 进程流由调用方接管 (McpManager StdioClientTransport)。
     */
    suspend fun launchProcess(id: String, command: String, cwd: String = ""): Process? = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext null
        manager.ensureWorkspace(workspace.root)
        manager.launchProcess(workspace.root, command, cwd)
    }

    suspend fun delete(id: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        dao.deleteById(id)
        withContext(Dispatchers.IO) {
            manager.deleteWorkspace(workspace.root)
        }
        cleanupAssistantReferences(id)
        return true
    }

    private suspend fun cleanupAssistantReferences(workspaceId: String) {
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.workspaceId?.toString() == workspaceId) {
                        assistant.copy(workspaceId = null)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    private suspend fun restoreShellState(workspace: WorkspaceEntity) {
        updateShellState(workspace.id, workspace.shellStatus)
    }

    private suspend fun updateShellState(
        workspace: WorkspaceEntity,
        shellStatus: String,
    ) = updateShellState(workspace.id, shellStatus)

    private suspend fun updateShellState(
        workspaceId: String,
        shellStatus: String,
    ) {
        dao.updateShellStatus(
            id = workspaceId,
            shellStatus = shellStatus,
            updatedAt = System.currentTimeMillis(),
        )
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val TOOLS_ASSET_DIR = "rin-tools"
    }
}
