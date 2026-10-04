/* 【域 A·对话核心】 — 页面 | 地图: docs/APP_MAP.md §A */
package me.rerere.rikkahub.ui.pages.videogen

/* ───【自研】VideoGenPage.kt — 视频生成页 (v4.8.102)
 * 抽屉「功能折叠」三件套之一 (翻译 / 图像 / 视频)。
 * UI 逻辑对齐图像生成页 (ImgGenPage):
 *   顶栏 (返回 + 新会话) → 结果区 (视频播放 + 保存) → 输入栏
 *   (接口选择弹层 (可编辑 URL/模型/密钥) / 生成设置弹层 / 参考图上传 / 描述框 / 发送·取消圆钮)
 *   生成中返回 → 取消确认弹窗; 错误走 toaster。
 * 接口配置 (用户定版) : 不走模型类型系统 — 直接在接口弹层里 ✎ 编辑 URL / 模型 ID / 密钥。
 * ───────────────────────────────────────────────────────────────*/

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.data.videogen.VideoGenEndpoint
import me.rerere.rikkahub.data.videogen.VideoGenEngine
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.render.VideoRenderView
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.ImageUtils
import org.koin.androidx.compose.koinViewModel
import java.io.File
import kotlin.uuid.Uuid

@Composable
fun VideoGenPage(
    vm: VideoGenVM = koinViewModel(),
) {
    val isGenerating by vm.isGenerating.collectAsStateWithLifecycle()
    var showCancelDialog by remember { mutableStateOf(false) }
    BackHandler(isGenerating) {
        showCancelDialog = true
    }
    if (showCancelDialog) {
        CancelDialog(
            onDismiss = { showCancelDialog = false },
            onConfirm = {
                showCancelDialog = false
                vm.cancelGeneration()
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.video_gen_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(onClick = vm::startNewSession) {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = "New session",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        VideoGenScreen(
            vm = vm,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        )
    }
}

@Composable
private fun CancelDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.video_gen_cancel_title)) },
        text = { Text(stringResource(R.string.video_gen_cancel_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun VideoGenScreen(
    vm: VideoGenVM,
    modifier: Modifier = Modifier,
) {
    val prompt by vm.prompt.collectAsStateWithLifecycle()
    val endpoints by vm.endpoints.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val duration by vm.duration.collectAsStateWithLifecycle()
    val resolution by vm.resolution.collectAsStateWithLifecycle()
    val aspect by vm.aspect.collectAsStateWithLifecycle()
    val watermark by vm.watermark.collectAsStateWithLifecycle()
    val referenceImages by vm.referenceImages.collectAsStateWithLifecycle()
    val isGenerating by vm.isGenerating.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val saved by vm.savedToWorkspace.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    var showSettingsSheet by remember { mutableStateOf(false) }
    var editingEndpoint by remember { mutableStateOf<VideoGenEndpoint?>(null) }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    val errorText = error?.let { e ->
        when (e) {
            "no_model" -> stringResource(R.string.video_gen_no_model)
            "not_configured" -> stringResource(R.string.video_gen_not_configured)
            else -> stringResource(R.string.video_gen_failed) + ": " + e
        }
    }
    LaunchedEffect(error) {
        errorText?.let { message ->
            toaster.show(message = message, type = ToastType.Error)
            vm.clearError()
        }
    }

    val statusText = when (status) {
        VideoGenEngine.Status.SUBMITTING, VideoGenEngine.Status.RUNNING ->
            stringResource(R.string.video_gen_generating)
        VideoGenEngine.Status.QUEUED -> stringResource(R.string.video_gen_queued)
        VideoGenEngine.Status.DOWNLOADING -> stringResource(R.string.video_gen_downloading)
        null -> null
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .imePadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            result?.let { file ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black),
                    ) {
                        VideoRenderView(file)
                    }
                    TextButton(onClick = vm::saveToWorkspace, enabled = !saved) {
                        Text(
                            if (saved) stringResource(R.string.video_gen_saved)
                            else stringResource(R.string.video_gen_save_workspace),
                        )
                    }
                }
            }
            if (isGenerating) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ContainedLoadingIndicator()
                    statusText?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        VideoInputBar(
            prompt = prompt,
            vm = vm,
            isGenerating = isGenerating,
            referenceImages = referenceImages,
            endpoints = endpoints,
            selectedId = selectedId,
            onShowSettings = { showSettingsSheet = true },
            onEditEndpoint = { editingEndpoint = it },
            modifier = Modifier,
        )
    }

    if (showSettingsSheet) {
        VideoSettingsBottomSheet(
            vm = vm,
            duration = duration,
            resolution = resolution,
            aspect = aspect,
            watermark = watermark,
            sheetState = sheetState,
            onDismiss = { showSettingsSheet = false },
        )
    }

    editingEndpoint?.let { endpoint ->
        EndpointConfigDialog(
            endpoint = endpoint,
            onSave = { baseUrl, modelId, apiKey ->
                vm.updateEndpoint(endpoint.id, baseUrl, modelId, apiKey)
                editingEndpoint = null
            },
            onDismiss = { editingEndpoint = null },
        )
    }
}

@Composable
private fun VideoInputBar(
    prompt: String,
    vm: VideoGenVM,
    isGenerating: Boolean,
    referenceImages: List<String>,
    endpoints: List<VideoGenEndpoint>,
    selectedId: String?,
    onShowSettings: () -> Unit,
    onEditEndpoint: (VideoGenEndpoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                scope.launch {
                    val paths = selectedUris.mapNotNull { uri ->
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val bitmap = ImageUtils.loadOptimizedBitmap(context, uri, maxSize = 2048)
                                    ?: error("Failed to decode image")
                                val pngBytes = FileUtils.compressBitmapToPng(bitmap)
                                bitmap.recycle()
                                val file = File(context.appTempFolder, "videogen_ref_${Uuid.random()}.png")
                                file.writeBytes(pngBytes)
                                file.absolutePath
                            }.getOrNull()
                        }
                    }
                    vm.addReferenceImages(paths)
                }
            }
        }
    var showEndpointSheet by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (referenceImages.isNotEmpty()) {
            ReferenceImagesRow(
                images = referenceImages,
                onRemove = vm::removeReferenceImage,
            )
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = vm::updatePrompt,
            placeholder = { Text(stringResource(R.string.video_gen_prompt_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 140.dp),
            minLines = 1,
            maxLines = 5,
            shape = MaterialTheme.shapes.large,
            textStyle = MaterialTheme.typography.bodySmall,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { showEndpointSheet = true },
            ) {
                Icon(
                    imageVector = HugeIcons.Video01,
                    contentDescription = stringResource(R.string.video_gen_model),
                )
            }

            IconButton(
                onClick = onShowSettings,
            ) {
                Icon(HugeIcons.Tools, null)
            }

            IconButton(
                onClick = { imagePickerLauncher.launch("image/*") },
            ) {
                Icon(
                    imageVector = HugeIcons.Add01,
                    contentDescription = "Add reference image",
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            val canSend = prompt.isNotBlank()
            Surface(
                onClick = {
                    if (!isGenerating) {
                        vm.generate()
                    } else {
                        vm.cancelGeneration()
                    }
                },
                enabled = isGenerating || canSend,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = when {
                    isGenerating -> MaterialTheme.colorScheme.errorContainer
                    !canSend -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> MaterialTheme.colorScheme.primary
                },
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isGenerating) HugeIcons.Cancel01 else HugeIcons.ArrowUp02,
                        contentDescription = stringResource(R.string.video_gen_generate),
                        tint = when {
                            isGenerating -> MaterialTheme.colorScheme.onErrorContainer
                            !canSend -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            else -> MaterialTheme.colorScheme.onPrimary
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }

    if (showEndpointSheet) {
        EndpointSheet(
            endpoints = endpoints,
            currentId = selectedId,
            onSelect = {
                vm.selectEndpoint(it)
                showEndpointSheet = false
            },
            onEdit = { endpoint ->
                showEndpointSheet = false
                onEditEndpoint(endpoint)
            },
            onDismiss = { showEndpointSheet = false },
        )
    }
}

@Composable
private fun ReferenceImagesRow(
    images: List<String>,
    onRemove: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        images.forEach { image ->
            Surface(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Box {
                    AsyncImage(
                        model = File(image),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )

                    Surface(
                        onClick = { onRemove(image) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .size(20.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.inverseOnSurface,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndpointSheet(
    endpoints: List<VideoGenEndpoint>,
    currentId: String?,
    onSelect: (String) -> Unit,
    onEdit: (VideoGenEndpoint) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxHeight(0.8f)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.video_gen_model),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            if (endpoints.isEmpty()) {
                Text(
                    text = stringResource(R.string.video_gen_no_model),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp),
                )
            } else {
                LazyColumn {
                    items(endpoints, key = { it.id }) { endpoint ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = endpoint.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = if (endpoint.configured) {
                                        endpoint.modelId
                                    } else {
                                        stringResource(R.string.video_gen_not_configured)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = endpoint.id == currentId,
                                    onClick = { onSelect(endpoint.id) },
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = { onEdit(endpoint) }) {
                                    Icon(
                                        imageVector = HugeIcons.PencilEdit01,
                                        contentDescription = stringResource(R.string.video_gen_edit),
                                    )
                                }
                            },
                            modifier = Modifier.clickable { onSelect(endpoint.id) },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun EndpointConfigDialog(
    endpoint: VideoGenEndpoint,
    onSave: (baseUrl: String, modelId: String, apiKey: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var baseUrl by remember { mutableStateOf(endpoint.baseUrl) }
    var modelId by remember { mutableStateOf(endpoint.modelId) }
    var apiKey by remember { mutableStateOf(endpoint.apiKey) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(endpoint.name + " · " + stringResource(R.string.video_gen_cfg_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.video_gen_cfg_url)) },
                    singleLine = false,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text(stringResource(R.string.video_gen_cfg_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.video_gen_cfg_key)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(baseUrl, modelId, apiKey) },
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoSettingsBottomSheet(
    vm: VideoGenVM,
    duration: Int?,
    resolution: String?,
    aspect: String?,
    watermark: Boolean,
    sheetState: SheetState,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.video_gen_settings_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            val defaultLabel = stringResource(R.string.video_gen_default)

            FormItem(
                label = { Text(stringResource(R.string.video_gen_duration)) },
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf<Pair<Int?, String>>(
                        null to defaultLabel,
                        5 to "5s",
                        10 to "10s",
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = duration == value,
                            onClick = { vm.updateDuration(value) },
                            label = { Text(label) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedNumberInput(
                    value = duration ?: 0,
                    onValueChange = { value -> vm.updateDuration(value.takeIf { it > 0 }) },
                    label = "Custom seconds",
                    modifier = Modifier.width(140.dp),
                )
            }

            FormItem(
                label = { Text(stringResource(R.string.video_gen_resolution)) },
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf<Pair<String?, String>>(
                        null to defaultLabel,
                        "720P" to "720P",
                        "1080P" to "1080P",
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = resolution == value,
                            onClick = { vm.updateResolution(value) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            FormItem(
                label = { Text(stringResource(R.string.video_gen_aspect)) },
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf<Pair<String?, String>>(
                        null to defaultLabel,
                        "16:9" to "16:9",
                        "9:16" to "9:16",
                        "1:1" to "1:1",
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = aspect == value,
                            onClick = { vm.updateAspect(value) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            FormItem(
                label = { Text(stringResource(R.string.video_gen_watermark)) },
                tail = {
                    Switch(
                        checked = watermark,
                        onCheckedChange = vm::updateWatermark,
                    )
                },
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
