/* 【域 A·对话核心】 — AI 组件 | 地图: docs/APP_MAP.md §A */
package me.rerere.rikkahub.ui.components.ai

/* ───【原版对齐】ChatAttachmentPicker.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.dokar.sonner.ToastType
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.isAllowedFileType
import org.koin.compose.koinInject
import java.io.File
import kotlin.uuid.Uuid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal data class ChatAttachmentPickerActions(
    val onTakePicture: () -> Unit,
    val onPickImage: () -> Unit,
    val onPickVideo: () -> Unit,
    val onPickAudio: () -> Unit,
    val onPickFile: () -> Unit,
)

@Composable
internal fun rememberChatAttachmentPickerActions(
    inputState: ChatInputState,
    setting: Settings,
    onAttachmentAdded: () -> Unit,
): ChatAttachmentPickerActions {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()
    val cameraPermission = rememberPermissionState(PermissionCamera)
    PermissionManager(permissionState = cameraPermission)

    // v4.8.112 (用户实证"拍完的照片小概率丢失"): 相机输出状态用 rememberSaveable —
    // 相机全屏期间 Activity 被低内存回收/重建后, 回执依然到达但 remember 状态已丢,
    // 旧实现拿 null → 照片被静默删除。路径 (String) 可安全跨进程恢复。
    var cameraOutputPath by rememberSaveable { mutableStateOf<String?>(null) }
    fun cameraFileNow(): File? = cameraOutputPath?.let { File(it) }
    val (_, launchCameraCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            onAttachmentAdded()
        },
        onCleanup = {
            cameraFileNow()?.delete()
            cameraOutputPath = null
        }
    )
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
        if (captureSuccessful) {
            // v4.8.112: 兜底恢复 — 状态仍缺失时找回最近拍摄的缓存照片 (15 分钟窗口)
            val file = cameraFileNow()
                ?: recoverRecentCameraCapture(context)?.also { cameraOutputPath = it.absolutePath }
            if (file != null && file.exists()) {
                if (setting.displaySetting.skipCropImage) {
                    inputState.addImages(filesManager.createChatFilesByContents(listOf(file.toUri())))
                    file.delete()
                    cameraOutputPath = null
                    onAttachmentAdded()
                } else {
                    launchCameraCrop(file.toUri())
                }
            } else {
                // v4.8.112: 失败可见 — 照片确实找不到时明确提示 (禁止静默丢弃)
                toaster.show(
                    resources.getString(R.string.chat_input_file_read_failed, "camera"),
                    type = ToastType.Error,
                )
                cameraFileNow()?.delete()
                cameraOutputPath = null
            }
        } else {
            cameraFileNow()?.delete()
            cameraOutputPath = null
        }
    }
    val onTakePicture: () -> Unit = {
        if (cameraPermission.allRequiredPermissionsGranted) {
            val file = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            cameraOutputPath = file.absolutePath
            cameraLauncher.launch(uri)
        } else {
            cameraPermission.requestPermissions()
        }
    }

    // v4.8.112: 同上 — 裁剪流程被重建打断时, 清理与引用均以路径状态为准
    var preCropTempPath by rememberSaveable { mutableStateOf<String?>(null) }
    fun preCropFileNow(): File? = preCropTempPath?.let { File(it) }
    val (_, launchImageCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            onAttachmentAdded()
        },
        onCleanup = {
            preCropFileNow()?.delete()
            preCropTempPath = null
        }
    )
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                Log.d("ImagePickButton", "Selected URIs: $selectedUris")
                if (setting.displaySetting.skipCropImage) {
                    inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                    onAttachmentAdded()
                } else if (selectedUris.size == 1) {
                    val tempFile = File(context.appTempFolder, "pick_temp_${System.currentTimeMillis()}.jpg")
                    runCatching {
                        val source = selectedUris.first()
                        // HEIF/HEIC（尤其 HDR HEIF）交给 UCrop 前先解码转为 JPEG，规避裁剪解码失败
                        val converted = ImageUtils.isHeifImage(context, source) &&
                            ImageUtils.convertHeifToJpeg(context, source, tempFile)
                        if (!converted) {
                            context.contentResolver.openInputStream(source)?.use { input ->
                                tempFile.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        preCropTempPath = tempFile.absolutePath
                        launchImageCrop(tempFile.toUri())
                    }.onFailure {
                        Log.e("ImagePickButton", "Failed to copy image to temp, falling back", it)
                        launchImageCrop(selectedUris.first())
                    }
                } else {
                    inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                    onAttachmentAdded()
                }
            } else {
                Log.d("ImagePickButton", "No images selected")
            }
        }

    val videoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                inputState.addVideos(filesManager.createChatFilesByContents(selectedUris))
                onAttachmentAdded()
            }
        }

    val audioPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                inputState.addAudios(filesManager.createChatFilesByContents(selectedUris))
                onAttachmentAdded()
            }
        }

    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                val documents = uris.mapNotNull { uri ->
                    val fileName = filesManager.getFileNameFromUri(uri) ?: "file"
                    val mime = filesManager.getFileMimeType(uri) ?: "text/plain"
                    if (isAllowedFileType(fileName, mime)) {
                        val localUri = filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()
                            ?: run {
                                toaster.show(
                                    resources.getString(R.string.chat_input_file_read_failed, fileName),
                                    type = ToastType.Error
                                )
                                return@mapNotNull null
                            }
                        UIMessagePart.Document(url = localUri.toString(), fileName = fileName, mime = mime)
                    } else {
                        toaster.show(
                            resources.getString(R.string.chat_input_unsupported_file_type, fileName),
                            type = ToastType.Error
                        )
                        null
                    }
                }
                if (documents.isNotEmpty()) {
                    inputState.addFiles(documents)
                    onAttachmentAdded()
                }
            }
        }

    return ChatAttachmentPickerActions(
        onTakePicture = onTakePicture,
        onPickImage = { imagePickerLauncher.launch("image/*") },
        onPickVideo = { videoPickerLauncher.launch("video/*") },
        onPickAudio = { audioPickerLauncher.launch("audio/*") },
        onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
    )
}

/**
 * v4.8.112: 相机回执兜底 — 状态丢失 (极端进程回收且保存态未覆盖) 时找回最近拍摄的
 * 缓存照片。仅当 TakePicture 回执为成功时调用; 15 分钟窗口 + 命名前缀双重限定,
 * 不误取旧文件 (正常路径的照片在消费后即被删除)。
 */
private fun recoverRecentCameraCapture(context: android.content.Context): File? {
    val cutoff = System.currentTimeMillis() - 15 * 60 * 1000L
    return context.cacheDir.listFiles { f ->
        f.isFile && f.name.startsWith("camera_") && f.name.endsWith(".jpg") && f.lastModified() >= cutoff
    }?.maxByOrNull { it.lastModified() }
}
