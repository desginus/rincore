package me.rerere.rikkahub.ui.components.ai

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.ui.sketch.SketchDialog
import me.rerere.ui.sketch.SketchResult
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

internal data class ChatAttachmentPickerActions(
    val onTakePicture: () -> Unit,
    val onPickImage: () -> Unit,
    val onPickVideo: () -> Unit,
    val onPickAudio: () -> Unit,
    val onPickFile: () -> Unit,
    val onSketch: () -> Unit,
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
    // v5.0.72：草图落盘用全进程 scope（绝不因 Composable 销毁/页面切换被取消）
    val appScope: me.rerere.rikkahub.AppScope = koinInject()
    val filesManager: FilesManager = koinInject()
    val cameraPermission = rememberPermissionState(PermissionCamera)
    PermissionManager(permissionState = cameraPermission)

    /**
     * v5.0.72 ★输入模块重设计 · 附件写入的**唯一入口**。
     *
     * 旧实现的问题（三条路径共同病灶）：
     *   · `inputState.addImages(createChatFilesByContents(...))` —— 失败返回**空列表**，
     *     `addImages(emptyList())` 什么也不做也不报错 → 图静默消失；
     *   · v5.0.70 的修法虽然补了 toast，但写文件是**同步**的 ——
     *     一张 20MP 的图在主线程上读+写会直接卡住输入栏；
     *   · 协程若挂在 `rememberCoroutineScope()` 上，页面/sheet 一变就可能被取消。
     *
     * 新契约：
     *   1. **异步**：一律在 [appScope]（全进程 SupervisorJob）上跑，不占用、也不会随 UI 取消；
     *   2. **零静默**：读失败/写失败/产出空列表 —— 三种情况都必 toast；
     *   3. **单一路径**：拍照、裁剪、选图、草图全部经过这里，不再各写一套。
     */
    fun addLocalImagesAsync(sources: List<Uri>, onSuccess: () -> Unit = {}) {
        if (sources.isEmpty()) return
        appScope.launch {
            val uris = runCatching { filesManager.createChatFilesByContents(sources) }
                .getOrElse { e ->
                    toaster.show(
                        "图片保存失败：${e.message ?: e::class.simpleName}",
                        type = ToastType.Error,
                    )
                    return@launch
                }
            if (uris.isEmpty()) {
                // v5.0.74 ★「拍完照提示图片保存失败」根修之一：
                // createChatFilesByContents 内部是 `uris.forEach { runCatching { ... } }`
                // —— 异常被**逐项吞掉**，上层只能看到空列表，用户只得到"未能生成附件"，
                // 永远看不到真正原因（打不开输入流/文件不存在/MIME 解析失败…）。
                // 这里对失败的 uri 做一次"单独复算"，把真实异常抓出来显示。
                val reason = sources.firstNotNullOfOrNull { uri ->
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.read() }
                            ?: error("无法打开输入流（contentResolver 返回 null）")
                        null
                    }.exceptionOrNull()?.let { e ->
                        "${uri} → ${e.message ?: e::class.simpleName}"
                    }
                }
                toaster.show(
                    "图片保存失败：未能生成附件" + (reason?.let { "（$it）" } ?: ""),
                    type = ToastType.Error,
                )
                return@launch
            }
            inputState.addImages(uris)
            onAttachmentAdded()
            onSuccess()
        }
    }

    /**
     * v5.0.74 ★拍照专用：**直接读本地文件**，完全绕开 content:// 解析链。
     *
     * 原因：拍照产生的 `cameraOutputFile` 就是我们自己 `cacheDir` 下的普通文件。
     * 旧实现把它转成 `FileProvider` 的 `content://` 再交给
     * `createChatFilesByContents`（内部 `getFileNameFromUri` / `getFileMimeType` /
     * `contentResolver.openInputStream` 三步，任何一步失败整张图就没了），
     * 这是"拍完照提示保存失败"的结构性来源。
     * 直接 `readBytes()` 落盘，不依赖 URI 解析，失败也必定带原因。
     */
    fun addLocalFileAsync(file: File, onSuccess: () -> Unit = {}) {
        appScope.launch {
            if (!file.isFile) {
                toaster.show("图片保存失败：拍照文件不存在（${file.name}）", type = ToastType.Error)
                return@launch
            }
            val bytes = runCatching { file.readBytes() }.getOrElse { e ->
                toaster.show(
                    "图片保存失败：${e.message ?: e::class.simpleName}",
                    type = ToastType.Error,
                )
                return@launch
            }
            if (bytes.isEmpty()) {
                toaster.show("图片保存失败：拍照文件为空（0 字节）", type = ToastType.Error)
                return@launch
            }
            val uris = runCatching {
                filesManager.createChatFilesByByteArrays(
                    byteArrays = listOf(bytes),
                    displayName = file.name,
                    mimeType = "image/jpeg",
                )
            }.getOrElse { e ->
                toaster.show(
                    "图片保存失败：${e.message ?: e::class.simpleName}",
                    type = ToastType.Error,
                )
                return@launch
            }
            if (uris.isEmpty()) {
                toaster.show("图片保存失败：未能生成附件文件", type = ToastType.Error)
                return@launch
            }
            inputState.addImages(uris)
            onAttachmentAdded()
            onSuccess()
        }
    }

    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }
    val (_, launchCameraCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            addLocalImagesAsync(listOf(croppedUri))
        },
        onCleanup = {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    )
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
        // v5.0.74 ★拍照链路重写：以**本地文件**为准，不再以 content:// uri 为准。
        // 旧实现 `captureSuccessful && cameraOutputUri != null` —— 而 cameraOutputUri 是
        // `remember`（非 rememberSaveable）：拍照会拉起系统相机，本进程被回收重建后它变 null
        // → 走 else 分支删文件、什么都不加（静默丢图）。文件句柄才是可靠依据。
        val file = cameraOutputFile
        if (captureSuccessful && file != null && file.isFile && file.length() > 0) {
            if (setting.displaySetting.skipCropImage) {
                addLocalFileAsync(file) {
                    runCatching { file.delete() }
                    cameraOutputFile = null
                    cameraOutputUri = null
                }
            } else {
                // 需要裁剪时仍交给 UCrop（它要一个可分享的 uri）
                val uri = cameraOutputUri ?: runCatching {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                    )
                }.getOrNull()
                if (uri != null) {
                    launchCameraCrop(uri)
                } else {
                    // 拿不到可分享 uri 就退化为不裁剪，绝不丢图
                    toaster.show("无法生成裁剪用的图片地址，已直接添加原图", type = ToastType.Normal)
                    addLocalFileAsync(file) {
                        runCatching { file.delete() }
                        cameraOutputFile = null
                        cameraOutputUri = null
                    }
                }
            }
        } else {
            val size = file?.length() ?: -1L
            if (captureSuccessful) {
                toaster.show(
                    "拍照失败：没有生成有效图片文件（$size 字节）",
                    type = ToastType.Error,
                )
            }
            runCatching { file?.delete() }
            cameraOutputFile = null
            cameraOutputUri = null
        }
    }
    val onTakePicture: () -> Unit = {
        if (cameraPermission.allRequiredPermissionsGranted) {
            cameraOutputFile = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
            cameraOutputUri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", cameraOutputFile!!
            )
            cameraLauncher.launch(cameraOutputUri!!)
        } else {
            cameraPermission.requestPermissions()
        }
    }

    var preCropTempFile by remember { mutableStateOf<File?>(null) }
    val (_, launchImageCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            addLocalImagesAsync(listOf(croppedUri))
        },
        onCleanup = {
            preCropTempFile?.delete()
            preCropTempFile = null
        }
    )
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                Log.d("ImagePickButton", "Selected URIs: $selectedUris")
                if (setting.displaySetting.skipCropImage) {
                    // v5.0.70：同草图/拍照，保存失败必须报出来（原来 addImages(emptyList) 静默丢图）
                    addLocalImagesAsync(selectedUris)
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
                        preCropTempFile = tempFile
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

    val scope = rememberCoroutineScope()
    var showSketch by remember { mutableStateOf(false) }
    if (showSketch) {
        // v5.0.72 ★草图链路重写（用户实测 v5.0.70 修法仍未生效：「画完点对号静默消失」）
        //
        // 逐段复核后确认旧实现仍有三个不确定点：
        //   ① 协程用的是 rememberCoroutineScope() —— 依附 ChatPageContent 组合；
        //      一旦这条链路在 sheet/弹层关闭、或页面切换的时序里被取消，
        //      `scope.launch` 的 body 根本不执行，**既无图也无 toast**（正是"静默"）。
        //   ② encode() 之后再 createChatFileBySketch() 又 encode 一遍；
        //      如果 bitmap 压缩失败返回空字节，会写出 **0 字节文件**，
        //      然后照常 addImages → 输入框里是一个加载不出来的空图（也像"丢了"）。
        //   ③ 失败时仍会关对话框，用户没有重试机会。
        //
        // 新设计（零静默、绝不丢）：
        //   · 同步校验 encode 结果，为空/异常 → **立即报错且不关对话框**（可重试）；
        //   · 落盘用 AppScope（全进程 SupervisorJob，**不随任何 Composable 销毁而取消**）；
        //   · 成功 → addImages + toast「已添加」；失败 → toast 原因 + 不关对话框。
        SketchDialog(
            onDismiss = { showSketch = false },
            onConfirm = { result ->
                val bytes = runCatching { result.encode() }.getOrElse { e ->
                    toaster.show(
                        "草图导出失败：${e.message ?: e::class.simpleName}",
                        type = ToastType.Error,
                    )
                    null
                }
                if (bytes == null || bytes.isEmpty()) {
                    if (bytes != null) {
                        toaster.show("草图导出失败：图片编码为空", type = ToastType.Error)
                    }
                    // 不关对话框 —— 用户可重画或重试
                } else {
                    appScope.launch {
                        val uri = runCatching {
                            filesManager.createChatFilesByByteArrays(
                                byteArrays = listOf(bytes),
                                displayName = "sketch.${result.extension}",
                                mimeType = result.mimeType,
                            ).firstOrNull()
                        }.getOrElse { e ->
                            toaster.show(
                                "草图保存失败：${e.message ?: e::class.simpleName}",
                                type = ToastType.Error,
                            )
                            null
                        }
                        if (uri != null) {
                            inputState.addImages(listOf(uri))
                            onAttachmentAdded()
                            toaster.show("草图已添加到输入框", type = ToastType.Success)
                            showSketch = false
                        } else {
                            // 不关对话框：让用户直接重试，而不是"关了、没了"
                            toaster.show("草图保存失败：未能生成附件文件", type = ToastType.Error)
                        }
                    }
                }
            },
        )
    }

    return ChatAttachmentPickerActions(
        onTakePicture = onTakePicture,
        onPickImage = { imagePickerLauncher.launch("image/*") },
        onPickVideo = { videoPickerLauncher.launch("video/*") },
        onPickAudio = { audioPickerLauncher.launch("audio/*") },
        onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
        onSketch = { showSketch = true },
    )
}

/** 把画板的结果存成聊天附件。 */
internal suspend fun FilesManager.createChatFileBySketch(result: SketchResult): Uri? = withContext(Dispatchers.IO) {
    createChatFilesByByteArrays(
        byteArrays = listOf(result.encode()),
        displayName = "sketch.${result.extension}",
        mimeType = result.mimeType,
    ).firstOrNull()
}
