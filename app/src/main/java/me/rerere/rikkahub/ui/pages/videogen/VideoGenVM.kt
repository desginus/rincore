/* 【域 A·对话核心】 — 页面 | 地图: docs/APP_MAP.md §A */
package me.rerere.rikkahub.ui.pages.videogen

/* ───【自研】VideoGenVM.kt — 视频生成 (v4.8.100, mediagen 标准生成链路)
 * 参数对照 mediagen 公共模型: prompt / resolution(清晰度档位) / aspectRatio /
 * durationSeconds / watermark; 模型来自设置里的媒体生成提供商 (kind=VIDEO)。
 * 链路: manager.generate (提交+轮询 Flow) → 终态 → 下载产出 → 本地播放/保存工作区。
 * ───────────────────────────────────────────────────────────────*/

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationStatus
import me.rerere.mediagen.provider.MediaGenerationManager
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.time.Duration.Companion.seconds

/** 模型选项 (供 UI 下拉; 键 = providerId|modelUuid 字符串形式)。 */
data class VideoModelOption(
    val key: String,
    val label: String,
    val provider: MediaGenerationProviderSetting,
    val model: MediaGenerationModel,
)

class VideoGenVM(
    context: Application,
    private val settingsStore: SettingsStore,
    private val manager: MediaGenerationManager,
    private val okHttpClient: OkHttpClient,
) : AndroidViewModel(context) {

    enum class Phase { IDLE, SUBMITTING, QUEUED, RUNNING }

    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt

    private val _selectedKey = MutableStateFlow<String?>(null)
    val selectedKey: StateFlow<String?> = _selectedKey

    private val _duration = MutableStateFlow<Int?>(null)
    val duration: StateFlow<Int?> = _duration

    private val _resolution = MutableStateFlow<String?>(null)
    val resolution: StateFlow<String?> = _resolution

    private val _aspect = MutableStateFlow<String?>(null)
    val aspect: StateFlow<String?> = _aspect

    private val _watermark = MutableStateFlow(false)
    val watermark: StateFlow<Boolean> = _watermark

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase

    /** 错误文本 (常规为提供方消息; "no_model" = 未配置视频模型的哨兵值) */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _result = MutableStateFlow<File?>(null)
    val result: StateFlow<File?> = _result

    private val _savedToWorkspace = MutableStateFlow(false)
    val savedToWorkspace: StateFlow<Boolean> = _savedToWorkspace

    private var cancelJob: Job? = null

    /** 全部可用视频模型 (mediaGenerationProviders × kind=VIDEO)。 */
    val videoModels: StateFlow<List<VideoModelOption>> = settingsStore.settingsFlow
        .map { settings ->
            settings.mediaGenerationProviders.flatMap { provider ->
                provider.models
                    .filter { it.kind == me.rerere.mediagen.model.MediaKind.VIDEO }
                    .map { model ->
                        VideoModelOption(
                            key = provider.id.toString() + "|" + model.id.toString(),
                            label = provider.name + " · " + model.modelId.let { if (it.isBlank()) model.displayName else it },
                            provider = provider,
                            model = model,
                        )
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun updatePrompt(text: String) { _prompt.value = text }
    fun selectModel(key: String) { _selectedKey.value = key }
    fun updateDuration(seconds: Int?) { _duration.value = seconds }
    fun updateResolution(value: String?) { _resolution.value = value }
    fun updateAspect(value: String?) { _aspect.value = value }
    fun updateWatermark(enabled: Boolean) { _watermark.value = enabled }
    fun clearError() { _error.value = null }

    fun generate() {
        val promptText = _prompt.value.trim()
        if (promptText.isEmpty()) return
        val all = videoModels.value
        val option = all.firstOrNull { it.key == _selectedKey.value } ?: all.firstOrNull()
        if (option == null) {
            _error.value = "no_model"
            return
        }
        cancelJob?.cancel()
        cancelJob = viewModelScope.launch {
            _isGenerating.value = true
            _error.value = null
            _result.value = null
            _savedToWorkspace.value = false
            _phase.value = Phase.SUBMITTING
            try {
                val request = MediaGenerationRequest(
                    prompt = promptText,
                    resolution = _resolution.value,
                    aspectRatio = _aspect.value,
                    durationSeconds = _duration.value,
                    watermark = _watermark.value.takeIf { it },
                )
                manager.generate(option.provider, option.model, request, interval = 15.seconds)
                    .collect { task ->
                        when (task.status) {
                            MediaGenerationStatus.QUEUED -> _phase.value = Phase.QUEUED
                            MediaGenerationStatus.RUNNING -> _phase.value = Phase.RUNNING
                            MediaGenerationStatus.SUCCEEDED -> {
                                val output = task.outputs.firstOrNull()
                                val file = output?.let { downloadOutput(it) }
                                if (file != null) {
                                    _result.value = file
                                } else {
                                    _error.value = "empty output"
                                }
                                _phase.value = Phase.IDLE
                            }
                            MediaGenerationStatus.FAILED,
                            MediaGenerationStatus.EXPIRED -> {
                                _error.value = task.error?.message ?: task.status.name
                                _phase.value = Phase.IDLE
                            }
                            MediaGenerationStatus.CANCELLED -> _phase.value = Phase.IDLE
                            else -> {}
                        }
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: e.javaClass.simpleName
            } finally {
                _isGenerating.value = false
                _phase.value = Phase.IDLE
            }
        }
    }

    fun cancelGeneration() {
        cancelJob?.cancel()
        cancelJob = null
        _isGenerating.value = false
        _phase.value = Phase.IDLE
    }

    /** 保存到工作区 (第一个可用工作区的「视频生成」目录)。 */
    fun saveToWorkspace() {
        val file = _result.value ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val koin = org.koin.core.context.GlobalContext.get()
                    val repo = koin.get<me.rerere.rikkahub.data.repository.WorkspaceRepository>()
                    val wsManager = koin.get<me.rerere.workspace.WorkspaceManager>()
                    val workspaces = repo.getAllWorkspaces()
                    val ws = workspaces.firstOrNull { wsManager.hasRootfs(it.root) } ?: workspaces.firstOrNull()
                        ?: return@runCatching false
                    val dir = File(wsManager.filesDir(ws.root), "视频生成").apply { mkdirs() }
                    file.copyTo(File(dir, file.name), overwrite = true)
                    true
                }.getOrDefault(false)
            }
            _savedToWorkspace.value = ok
            if (!ok) _error.value = "save failed"
        }
    }

    private suspend fun downloadOutput(output: me.rerere.mediagen.model.MediaGenerationOutput): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(getApplication<Application>().filesDir, "videogen").apply { mkdirs() }
                val ext = when {
                    output.mimeType.contains("webm", ignoreCase = true) -> "webm"
                    output.mimeType.contains("quicktime", ignoreCase = true) ||
                        output.mimeType.contains("mov", ignoreCase = true) -> "mov"
                    else -> "mp4"
                }
                val file = File(dir, "video_" + System.currentTimeMillis() + "." + ext)
                val inline = output.data
                if (inline != null) {
                    file.writeBytes(inline)
                } else {
                    val url = output.url ?: error("no output url")
                    val response = okHttpClient.newCall(Request.Builder().url(url).get().build()).execute()
                    response.use { r ->
                        if (!r.isSuccessful) error("download HTTP " + r.code)
                        val body = r.body ?: error("empty response")
                        body.byteStream().use { ins -> file.outputStream().use { outs -> ins.copyTo(outs) } }
                    }
                }
                file
            }.getOrNull()
        }
}
