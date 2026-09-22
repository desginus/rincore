package me.rerere.rikkahub.ui.pages.extensions.workspace


/* ───【原版对齐】WorkspaceDetailVM | 差异 +77 行
 * 来源: 原版移植 + 自研 (工作区状态管理)
 * 差异: rootfs 安装/Shell 状态自研状态机
 * ───────────────────────────────────────────────────────────────*/
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceStorageArea

class WorkspaceDetailVM(
    private val id: String,
    initialPath: String,
    private val repository: WorkspaceRepository,
) : ViewModel() {
    // v4.7.23: 初始浏览目录 — "应用文件"入口传对话 CWD (绝对 /workspace/... 前缀),
    // 转为 state 内的相对路径; 其他入口传空串 = 顶层。
    private val _state = MutableStateFlow(
        WorkspaceDetailState(
            path = initialPath.removePrefix("/workspace/").removePrefix("/workspace").removePrefix("/"),
        )
    )
    val state = _state.asStateFlow()

    private val _terminalState = MutableStateFlow(WorkspaceTerminalState())
    val terminalState = _terminalState.asStateFlow()

    private val _installProgress = MutableStateFlow<RootfsInstallProgress?>(null)
    val installProgress = _installProgress.asStateFlow()

    private val _installError = MutableStateFlow<String?>(null)
    val installError = _installError.asStateFlow()

    private val _settingsError = MutableStateFlow<String?>(null)
    val settingsError = _settingsError.asStateFlow()

    fun dismissSettingsError() {
        _settingsError.value = null
    }

    init {
        loadWorkspace()
        refresh()
    }

    fun selectArea(area: WorkspaceStorageArea) {
        _state.update {
            it.copy(
                area = area,
                path = "",
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun open(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        _state.update { it.copy(path = entry.path, entries = emptyList(), error = null) }
        refresh()
    }

    fun goUp() {
        val path = state.value.path
        if (path.isBlank()) return
        _state.update {
            it.copy(
                path = path.substringBeforeLast('/', missingDelimiterValue = ""),
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                repository.listFiles(
                    id = id,
                    area = state.value.area,
                    path = state.value.path,
                )
            }.onSuccess { entries ->
                _state.update { it.copy(entries = entries, loading = false) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        entries = emptyList(),
                        loading = false,
                        error = error.message ?: "加载工作区文件失败",
                    )
                }
            }
        }
    }

    fun delete(entry: WorkspaceFileEntry) {
        viewModelScope.launch {
            runCatching {
                repository.deleteFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    recursive = entry.isDirectory,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "删除失败") }
            }
        }
    }

    // v3.22.0: 文件预览 — shareFile 已封装宿主 File 解析 (缓存拷贝),
    // onReady 回调内直接置 state (StateFlow 线程安全)
    fun openPreview(entry: WorkspaceFileEntry, cacheDir: File) {
        _state.update { it.copy(previewEntry = entry) }
        shareFile(entry, java.io.File(cacheDir, "workspace_preview").apply { mkdirs() }) { f ->
            _state.update { it.copy(previewFile = f) }
        }
    }

    fun closePreview() {
        _state.update { it.copy(previewFile = null, previewEntry = null) }
    }

    fun importFile(inputStream: InputStream, fileName: String) {
        viewModelScope.launch {
            // v4.8.4: 传输计数包裹 — 上传期间 UI 显示圆形加载指示
            _state.update { it.copy(activeTransfers = it.activeTransfers + 1) }
            try {
                runCatching {
                    repository.importFile(
                        id = id,
                        area = state.value.area,
                        destinationPath = state.value.path,
                        fileName = fileName,
                        inputStream = inputStream,
                    )
                }.onSuccess {
                    refresh()
                }.onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "导入文件失败") }
                }
            } finally {
                _state.update { it.copy(activeTransfers = (it.activeTransfers - 1).coerceAtLeast(0)) }
            }
        }
    }

    suspend fun resolveImageFile(
        entry: WorkspaceFileEntry,
        area: WorkspaceStorageArea,
    ): File = repository.resolveFile(id, area, entry.path)

    fun exportFile(entry: WorkspaceFileEntry, outputStream: OutputStream) {
        viewModelScope.launch {
            // v4.8.4: 传输计数包裹 — 导出期间 UI 显示圆形加载指示
            _state.update { it.copy(activeTransfers = it.activeTransfers + 1) }
            try {
                runCatching {
                    // v4.5.7: exportFile 契约变更 — 不再关闭传入流, 由调用方管理
                    outputStream.use { output ->
                        repository.exportFile(
                            id = id,
                            area = state.value.area,
                            path = entry.path,
                            outputStream = output,
                        )
                    }
                }.onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "导出文件失败") }
                }
            } finally {
                _state.update { it.copy(activeTransfers = (it.activeTransfers - 1).coerceAtLeast(0)) }
            }
        }
    }

    fun shareFile(entry: WorkspaceFileEntry, cacheDir: File, onReady: (File) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val dir = File(cacheDir, "workspace_share").apply { mkdirs() }
                val file = File(dir, entry.name)
                file.outputStream().use { output ->
                    repository.exportFile(
                        id = id,
                        area = state.value.area,
                        path = entry.path,
                        outputStream = output,
                    )
                }
                file
            }.onSuccess { file ->
                runCatching { onReady(file) }.onFailure { error ->
                    _state.update { it.copy(error = "分享启动失败: " + (error.message ?: error.toString())) }
                }
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "分享文件失败") }
            }
        }
    }

    /**
     * v4.5.3: 文件夹打包走纯 Kotlin 文件 IO (exportFolderZip), 不经 Rootfs
     * shell — 无临时产物、无解释器依赖; 失败经 state.error 显式呈现, 空产物
     * 直接报错, 不再静默。
     */
    fun shareFolder(entry: WorkspaceFileEntry, cacheDir: File, onReady: (File) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val dir = File(cacheDir, "workspace_share").apply { mkdirs() }
                val zipName = entry.name.trimEnd('/') + ".zip"
                val file = File(dir, zipName)
                val count = file.outputStream().use { output ->
                    repository.exportFolderZip(
                        id = id,
                        area = state.value.area,
                        folderPath = entry.path,
                        outputStream = output,
                    )
                }
                require(file.length() > 0) { "打包产物为空" }
                android.util.Log.i("WorkspaceShare", "folder zip ready: " + file.absolutePath + " bytes=" + file.length() + " entries=" + count)
                file
            }.onSuccess { file ->
                // v4.5.10: onReady (FileProvider/Intent) 的异常必须可见 —
                // 此前 onSuccess 回调抛错被协程吞掉, 表现为"点分享没反应"
                runCatching { onReady(file) }.onFailure { error ->
                    _state.update { it.copy(error = "分享启动失败: " + (error.message ?: error.toString())) }
                }
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "分享文件夹失败: " + (error.message ?: "")) }
            }
        }
    }

    fun exportFolder(entry: WorkspaceFileEntry, outputStream: OutputStream) {
        viewModelScope.launch {
            // v4.8.4: 传输计数包裹 — 文件夹导出期间 UI 显示圆形加载指示
            _state.update { it.copy(activeTransfers = it.activeTransfers + 1) }
            try {
                runCatching {
                    repository.exportFolderZip(
                        id = id,
                        area = state.value.area,
                        folderPath = entry.path,
                        outputStream = outputStream,
                    )
                }.onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "导出文件夹失败: " + (error.message ?: "")) }
                }
            } finally {
                _state.update { it.copy(activeTransfers = (it.activeTransfers - 1).coerceAtLeast(0)) }
            }
        }
    }

    // ═══ 2.5.3 移植: 长按多选 + 批量导出 ═══
    private var pendingExport: Pair<WorkspaceStorageArea, List<WorkspaceFileEntry>>? = null

    fun toggleSelection(entry: WorkspaceFileEntry) {
        if (!state.value.selectionMode) return
        _state.update { s ->
            val next = s.selectedPaths.toMutableSet().apply {
                if (!add(entry.path)) remove(entry.path)
            }
            s.copy(selectedPaths = next)
        }
    }

    fun enterSelectionMode(entry: WorkspaceFileEntry) {
        _state.update { it.copy(selectionMode = true, selectedPaths = setOf(entry.path)) }
    }

    fun exitSelectionMode() {
        _state.update { it.copy(selectionMode = false, selectedPaths = emptySet()) }
    }

    fun selectAllVisible() {
        _state.update { s ->
            s.copy(selectedPaths = s.entries.filterNot { it.isDirectory }.map { it.path }.toSet())
        }
    }

    fun prepareBatchExport(): Boolean {
        val s = state.value
        val files = s.entries.filter { it.path in s.selectedPaths && !it.isDirectory }
        if (pendingExport != null || s.exporting || files.isEmpty()) return false
        pendingExport = s.area to files
        return true
    }

    fun dismissExportResult() {
        _state.update { it.copy(exportResult = null) }
    }

    fun exportFilesToDirectory(treeUri: Uri?, resolver: ContentResolver) {
        val (area, entries) = pendingExport.also { pendingExport = null } ?: return
        if (treeUri == null) return
        _state.update { it.copy(exporting = true, exportCompleted = 0, exportTotal = entries.size, exportResult = null) }
        viewModelScope.launch {
            var succeeded = 0
            val failures = mutableListOf<String>()
            try {
                withContext(Dispatchers.IO) {
                    val parent = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, DocumentsContract.getTreeDocumentId(treeUri)
                    )
                    entries.forEachIndexed { index, entry ->
                        ensureActive()
                        var destination: Uri? = null
                        try {
                            if (entry.isDirectory) {
                                // v4.8.5: 文件夹 → 递归打包 .zip。旧实现走 exportFile 对目录
                                // 抛 "Path is not a file" — 批量导出中的文件夹全部失败 (内容丢失实证)。
                                val zipName = entry.name.trimEnd('/') + ".zip"
                                val document = DocumentsContract.createDocument(
                                    resolver, parent, "application/zip", zipName
                                ) ?: error("无法创建目标文件")
                                destination = document
                                val output = resolver.openOutputStream(document) ?: error("无法打开目标文件")
                                output.use {
                                    repository.exportFolderZip(id = id, area = area, folderPath = entry.path, outputStream = it)
                                }
                                succeeded++
                                destination = null
                            } else {
                                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                    entry.name.substringAfterLast('.', "").lowercase()
                                ) ?: "application/octet-stream"
                                val document = DocumentsContract.createDocument(resolver, parent, mime, entry.name)
                                    ?: error("无法创建目标文件")
                                destination = document
                                val output = resolver.openOutputStream(document) ?: error("无法打开目标文件")
                                output.use { repository.exportFile(id = id, area = area, path = entry.path, outputStream = it) }
                                succeeded++
                                destination = null
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            failures += "${entry.name}：${error.message ?: "导出失败"}"
                        } finally {
                            // 只清理本次创建但未完整写入的文件
                            destination?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
                        }
                        _state.update { it.copy(exportCompleted = index + 1) }
                    }
                }
                _state.update {
                    it.copy(exportResult = buildString {
                        append("已导出 $succeeded/${entries.size} 个文件")
                        if (failures.isNotEmpty()) append("\n\n" + failures.joinToString("\n"))
                    }, selectionMode = false, selectedPaths = emptySet())
                }
            } catch (error: CancellationException) {
                _state.update { it.copy(exporting = false) }
            } catch (error: Exception) {
                _state.update { it.copy(exporting = false, exportResult = "批量导出失败: ${error.message ?: ""}") }
            }
        }
    }

    fun setShellCompatibilityMode(enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.setShellCompatibilityMode(id, enabled)
                val workspace = repository.getById(id)
                _state.update { it.copy(workspace = workspace) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _settingsError.value = error.message.orEmpty()
            }
        }
    }

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            repository.setToolApproval(workspace.id, toolName, needsApproval)
            loadWorkspace()
        }
    }

    fun renameFile(entry: WorkspaceFileEntry, newName: String) {
        viewModelScope.launch {
            runCatching {
                repository.renameFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    newName = newName,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "重命名失败") }
            }
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            runCatching {
                repository.createFolder(
                    id = id,
                    area = state.value.area,
                    path = state.value.path,
                    name = name,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "创建文件夹失败") }
            }
        }
    }

    fun createFile(name: String) {
        viewModelScope.launch {
            runCatching {
                repository.createFile(
                    id = id,
                    area = state.value.area,
                    path = state.value.path,
                    name = name,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "创建文件失败") }
            }
        }
    }

    fun moveFile(source: WorkspaceFileEntry, targetDir: String) {
        viewModelScope.launch {
            val targetPath = if (targetDir.isBlank()) "/${source.name}" else "$targetDir/${source.name}"
            runCatching {
                repository.moveFile(
                    id = id,
                    source = source.path,
                    target = targetPath,
                    overwrite = false,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "移动文件失败") }
            }
        }
    }

    fun installRootfs(url: String) {
        viewModelScope.launch {
            _installError.value = null
            val workspace = state.value.workspace ?: return@launch
            _installProgress.value = RootfsInstallProgress(stage = RootfsInstallStage.DOWNLOADING)
            try {
                repository.installRootfs(workspace.id, url) { progress ->
                    _installProgress.value = progress
                }
                loadWorkspace()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _installError.value = error.message ?: "Rootfs 安装失败"
            } finally {
                _installProgress.value = null
            }
        }
    }

    fun dismissInstallError() {
        _installError.value = null
    }

    fun executeTerminalCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return
        // 原子地完成「检查 running」与「置 running=true」, 避免两次快速提交并发启动两条命令
        val previous = _terminalState.getAndUpdate { state ->
            if (state.running) {
                state
            } else {
                state.copy(
                    running = true,
                    input = "",
                    history = state.history + WorkspaceTerminalEntry.Command(trimmed),
                )
            }
        }
        if (previous.running) return
        viewModelScope.launch {
            runCatching {
                repository.executeCommand(id, trimmed)
            }.onSuccess { result ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Result(result),
                    )
                }
            }.onFailure { error ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Error(error.message ?: "命令执行失败"),
                    )
                }
            }
        }
    }

    fun updateTerminalInput(input: String) {
        _terminalState.update { it.copy(input = input) }
    }

    fun clearTerminal() {
        _terminalState.update { it.copy(history = emptyList()) }
    }

    private fun loadWorkspace() {
        viewModelScope.launch {
            val workspace = repository.getById(id)
            _state.update { it.copy(workspace = workspace) }
        }
    }
}

data class WorkspaceDetailState(
    val workspace: WorkspaceEntity? = null,
    val area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    val path: String = "",
    val entries: List<WorkspaceFileEntry> = emptyList(),
    // 2.5.3 移植: 长按多选 + 批量导出
    val selectionMode: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    val exporting: Boolean = false,
    // v4.8.4: 文件传输计数 (上传/单文件导出/文件夹导出进行中 > 0) —
    // UI 据此显示圆形加载指示 (传输完成即消失)
    val activeTransfers: Int = 0,
    val exportCompleted: Int = 0,
    val exportTotal: Int = 0,
    val exportResult: String? = null,
    // v3.22.0: 文件预览 (解析出的宿主 File; null=无预览)
    val previewFile: File? = null,
    val previewEntry: WorkspaceFileEntry? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

data class WorkspaceTerminalState(
    val input: String = "",
    val running: Boolean = false,
    val history: List<WorkspaceTerminalEntry> = emptyList(),
)

sealed interface WorkspaceTerminalEntry {
    data class Command(val command: String) : WorkspaceTerminalEntry
    data class Result(val result: WorkspaceCommandResult) : WorkspaceTerminalEntry
    data class Error(val message: String) : WorkspaceTerminalEntry
}
