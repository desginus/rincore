package me.rerere.rikkahub.ui.pages.backup.tabs

import kotlinx.coroutines.withContext
import androidx.compose.material3.MaterialTheme
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileExport
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.ui.pages.backup.components.BackupActionIcon
import me.rerere.rikkahub.ui.pages.backup.components.backupContentItem
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun ImportExportTab(
    vm: BackupVM,
    onShowRestartDialog: () -> Unit
) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val selectedBackupItems by vm.localBackupItems.collectAsStateWithLifecycle()
    var isExporting by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var showImportConfirmDialog by remember { mutableStateOf(false) }

    // 导入类型：local 为本地备份，chatbox 为 Chatbox 导入，cherry 为 Cherry Studio 导入
    var importType by remember { mutableStateOf("local") }

    // 创建文件保存的launcher
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { targetUri ->
            scope.launch {
                isExporting = true
                runCatching {
                    // 导出文件
                    val exportFile = vm.exportToFile()

                    // 复制到用户选择的位置
                    // v5.0.44 用户实证：打包结束后的 20G 复制**没有任何进度**，
                    // 表现为「体积涨到差不多就忽然停住」→ 用户以为卡死/失败。
                    // 改为分块复制并实时上报（每 ~16MB 更新一次，含总量），进度文案="写入文件"。
                    val totalBytes = exportFile.length()
                    var written = 0L
                    vm.exportProgress = "写入文件" to 0L
                    context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                        FileInputStream(exportFile).use { inputStream ->
                            val buf = ByteArray(1 shl 20) // 1MB
                            while (true) {
                                val n = inputStream.read(buf)
                                if (n <= 0) break
                                outputStream.write(buf, 0, n)
                                written += n
                                if (written / (16L shl 20) != (written - n) / (16L shl 20)) {
                                    vm.exportProgress = "写入文件（共 ${"%.1f".format(totalBytes / 1024.0 / 1024.0)} MB）" to written
                                }
                            }
                        }
                        runCatching { outputStream.flush() }
                    }
                    vm.exportProgress = null

                    // 清理临时文件
                    exportFile.delete()

                    toaster.show(
                        context.getString(R.string.backup_page_backup_success),
                        type = ToastType.Success
                    )
                }.onFailure { e ->
                    e.printStackTrace()
                    toaster.show(
                        context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                        type = ToastType.Error
                    )
                }
                isExporting = false
            }
        }
    }

    // 创建文件选择的launcher
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { sourceUri ->
            scope.launch {
                isRestoring = true
                runCatching {
                    when (importType) {
                        "local" -> {
                            // 本地备份导入：处理zip文件
                            // v5.0.47 用户实证根因：此前 `scope.launch`（主线程）里做 `copyTo`，
                            // 15G 备份直接把主线程堵死 → ANR → 系统杀进程（"卡住→回主页→无日志"）。
                            // 改为 IO 线程 + 1MB 分块 + 实时进度 + 空间预检。
                            val tempFile =
                                File(context.cacheDir, "temp_restore_${System.currentTimeMillis()}.zip")

                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                // 空间预检：复制一份 + 解压一份，至少需要 2 倍备份体积的可用空间
                                val usable = context.cacheDir.usableSpace
                                val head = context.contentResolver.openInputStream(sourceUri)
                                head?.close()
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    vm.restoreProgress = "读取备份文件" to 0L
                                }
                                context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                    FileOutputStream(tempFile).use { outputStream ->
                                        val buf = ByteArray(1 shl 20)
                                        var copied = 0L
                                        while (true) {
                                            val n = inputStream.read(buf)
                                            if (n <= 0) break
                                            outputStream.write(buf, 0, n)
                                            copied += n
                                            if (copied / (64L shl 20) != (copied - n) / (64L shl 20)) {
                                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                    vm.restoreProgress = "读取备份文件" to copied
                                                }
                                            }
                                        }
                                        runCatching { outputStream.fd.sync() }
                                    }
                                }
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    vm.restoreProgress = "解包数据" to 0L
                                }
                            }

                            // 从临时文件恢复（内部已是 Dispatchers.IO）
                            vm.restoreFromLocalFile(tempFile)
                            vm.restoreProgress = null

                            // 清理临时文件
                            tempFile.delete()
                        }

                        "chatbox" -> {
                            // Chatbox Backup v2：处理 ZIP 文件
                            val tempFile =
                                File(context.cacheDir, "temp_chatbox_${System.currentTimeMillis()}.zip")

                            try {
                                context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                    FileOutputStream(tempFile).use { outputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                                }

                                vm.restoreFromChatBox(tempFile)
                            } finally {
                                tempFile.delete()
                            }
                        }

                        "cherry" -> {
                            // Cherry Studio导入：处理zip文件
                            val tempFile =
                                File(context.cacheDir, "temp_cherry_${System.currentTimeMillis()}.zip")

                            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                FileOutputStream(tempFile).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }

                            // 从Cherry Studio备份恢复
                            vm.restoreFromCherryStudio(tempFile)

                            // 清理临时文件
                            tempFile.delete()
                        }
                    }

                    toaster.show(
                        context.getString(R.string.backup_page_restore_success),
                        type = ToastType.Success
                    )
                    onShowRestartDialog()
                }.onFailure { e ->
                    e.printStackTrace()
                    toaster.show(
                        context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                        type = ToastType.Error
                    )
                }
                isRestoring = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CardGroup(
            title = { Text(stringResource(R.string.backup_page_local_backup_export)) },
        ) {
            item(
                headlineContent = {
                    Text(
                        "「文件与工作区」= 聊天附件 + 工作区全部内容（files 文件 + linux rootfs，含你装的 Python）；两者都必须勾选才是完整备份。",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                supportingContent = {},
            )
            backupContentItem(
                options = WebDavConfig.BackupItem.entries,
                selected = selectedBackupItems,
                onChange = { vm.updateLocalBackupItems(it) },
            ) { item ->
                when (item) {
                    WebDavConfig.BackupItem.DATABASE -> stringResource(R.string.backup_page_chat_records)
                    // v5.0.43 用户核验定案：工作区（含 rootfs/Python）打包在 FILES 里，
                    // 旧文案只写「文件」导致用户以为不含工作区 → 明确标注。
                    WebDavConfig.BackupItem.FILES -> "文件与工作区（含 rootfs）"
                }
            }
            item(
                onClick = if (!isExporting) {
                    {
                        val timestamp = LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                        createDocumentLauncher.launch("rikkahub_backup_$timestamp.zip")
                    }
                } else null,
                headlineContent = { Text(stringResource(R.string.backup_page_local_backup_export)) },
                supportingContent = {
                    val prog = vm.exportProgress
                    Text(
                        when {
                            // v5.0.41：显示阶段 + 已打包体积（工作区可能很大，让用户知道在动）
                            // v5.0.44：三阶段共用（打包工作区 / 归档工作区 / 写入文件），文案通用化
                            isExporting && prog != null ->
                                "${prog.first}：${"%.1f".format(prog.second / 1024.0 / 1024.0)} MB…"
                            // v5.0.45：导出是长任务，系统在后台可能杀进程 → 明确要求保持前台
                            isExporting -> "导出中…（请保持应用在前台，不要切走）"
                            else -> stringResource(R.string.backup_page_export_desc)
                        }
                    )
                },
                leadingContent = { BackupActionIcon(HugeIcons.FileExport, loading = isExporting) },
            )

            item(
                onClick = if (!isRestoring) {
                    {
                        showImportConfirmDialog = true
                    }
                } else null,
                headlineContent = { Text(stringResource(R.string.backup_page_local_backup_import)) },
                supportingContent = {
                    val rprog = vm.restoreProgress
                    Text(
                        when {
                            isRestoring && importType == "local" && rprog != null ->
                                "${rprog.first}：${"%.1f".format(rprog.second / 1024.0 / 1024.0)} MB…（请保持前台）"
                            isRestoring && importType == "local" -> stringResource(R.string.backup_page_importing)
                            else -> stringResource(R.string.backup_page_import_desc)
                        }
                    )
                },
                leadingContent = {
                    BackupActionIcon(HugeIcons.FileImport, loading = isRestoring && importType == "local")
                },
            )
        }

        CardGroup(
            title = { Text(stringResource(R.string.backup_page_import_from_other_app)) },
        ) {
            item(
                onClick = if (!isRestoring) {
                    {
                        importType = "chatbox"
                        openDocumentLauncher.launch(arrayOf("application/zip"))
                    }
                } else null,
                headlineContent = { Text(stringResource(R.string.backup_page_import_from_chatbox)) },
                supportingContent = { Text(stringResource(R.string.backup_page_import_chatbox_desc)) },
                leadingContent = {
                    BackupActionIcon(HugeIcons.FileImport, loading = isRestoring && importType == "chatbox")
                },
            )

            item(
                onClick = if (!isRestoring) {
                    {
                        importType = "cherry"
                        openDocumentLauncher.launch(arrayOf("application/zip"))
                    }
                } else null,
                headlineContent = { Text(stringResource(R.string.backup_page_import_from_cherry_studio)) },
                supportingContent = { Text(stringResource(R.string.backup_page_import_cherry_studio_desc)) },
                leadingContent = {
                    BackupActionIcon(HugeIcons.FileImport, loading = isRestoring && importType == "cherry")
                },
            )
        }
    }

    if (showImportConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showImportConfirmDialog = false },
            title = { Text(stringResource(R.string.backup_page_local_backup_import)) },
            text = { Text(stringResource(R.string.backup_page_import_overwrite_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirmDialog = false
                        importType = "local"
                        openDocumentLauncher.launch(arrayOf("application/zip"))
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
