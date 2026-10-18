/* 【域 A·对话核心】 — 页面 | 地图: docs/APP_MAP.md §A */
package me.rerere.rikkahub.ui.pages.videogen

/* ───【自研】VideoGenVM.kt — 视频生成 (v4.8.103)
 * 用户定版（路线一）: 与图像生成完全相同的流程 —
 *   设置→提供商 里把模型类型设为「视频」→ 视频生成页用标准模型选择器选它 →
 *   providerManager.getProviderByType(provider).generateVideo(...) 分发到提供方实现:
 *     · GoogleProvider     → Google Veo (Gemini API predictLongRunning)
 *     · OpenAIProvider     → 阿里云 HappyHorse (百炼 DashScope 异步任务; baseUrl 含 aliyuncs.com)
 * ───────────────────────────────────────────────────────────────*/

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.VideoGenerationParams
import me.rerere.ai.ui.VideoGenerationItem
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import java.io.File

class VideoGenVM(
    context: Application,
    val settingsStore: SettingsStore,
    val providerManager: ProviderManager,
) : AndroidViewModel(context) {

    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt

    private val _duration = MutableStateFlow<Int?>(null)
    val duration: StateFlow<Int?> = _duration

    private val _resolution = MutableStateFlow<String?>(null)
    val resolution: StateFlow<String?> = _resolution

    private val _aspect = MutableStateFlow<String?>(null)
    val aspect: StateFlow<String?> = _aspect

    private val _watermark = MutableStateFlow(false)
    val watermark: StateFlow<Boolean> = _watermark

    private val _referenceImages = MutableStateFlow<List<String>>(emptyList())
    val referenceImages: StateFlow<List<String>> = _referenceImages

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    /** 错误文本 (提供方可读消息; "no_model" 为未选择视频模型的哨兵值)。 */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _result = MutableStateFlow<File?>(null)
    val result: StateFlow<File?> = _result

    private val _savedToWorkspace = MutableStateFlow(false)
    val savedToWorkspace: StateFlow<Boolean> = _savedToWorkspace

    private var cancelJob: Job? = null

    fun updatePrompt(text: String) { _prompt.value = text }
    fun updateDuration(seconds: Int?) { _duration.value = seconds }
    fun updateResolution(value: String?) { _resolution.value = value }
    fun updateAspect(value: String?) { _aspect.value = value }
    fun updateWatermark(enabled: Boolean) { _watermark.value = enabled }
    fun clearError() { _error.value = null }

    fun addReferenceImages(paths: List<String>) {
        _referenceImages.value = (_referenceImages.value + paths).distinct().take(MAX_REFERENCE_IMAGES)
    }

    fun removeReferenceImage(path: String) {
        _referenceImages.value = _referenceImages.value.filterNot { it == path }
        deleteReferenceFiles(listOf(path))
    }

    /** 新会话 (对齐图像生成: 取消在跑任务 + 清空全部输入与结果)。 */
    fun startNewSession() {
        cancelJob?.cancel()
        cancelJob = null
        deleteReferenceFiles(_referenceImages.value)
        _referenceImages.value = emptyList()
        _prompt.value = ""
        _result.value = null
        _error.value = null
        _isGenerating.value = false
        _savedToWorkspace.value = false
    }

    fun generateVideo() {
        val promptText = _prompt.value.trim()
        if (promptText.isEmpty()) return
        val firstFrame = _referenceImages.value.firstOrNull()
        cancelJob?.cancel()
        cancelJob = viewModelScope.launch {
            _isGenerating.value = true
            _error.value = null
            _result.value = null
            _savedToWorkspace.value = false
            try {
                val settings = settingsStore.settingsFlow.first()
                val model = settings.findModelById(settings.videoGenerationModelId)
                    ?: throw IllegalStateException("no_model")
                if (model.type != ModelType.VIDEO) throw IllegalStateException("no_model")
                val provider = model.findProvider(settings.providers)
                    ?: throw IllegalStateException("未找到提供方")
                val params = VideoGenerationParams(
                    model = model,
                    prompt = promptText,
                    numOfVideos = 1,
                    resolution = _resolution.value.orEmpty(),
                    aspectRatio = _aspect.value.orEmpty(),
                    durationSeconds = _duration.value,
                    watermark = _watermark.value,
                    firstFrame = firstFrame,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                )

                var produced: VideoGenerationItem? = null
                providerManager.getProviderByType(provider)
                    .generateVideo(provider, params)
                    .collect { item -> produced = item }
                val item = produced ?: throw IllegalStateException("生成结束但没有产出")

                val dest = withContext(Dispatchers.IO) {
                    val dir = File(getApplication<Application>().filesDir, "videogen").apply { mkdirs() }
                    val file = File(dir, "video_" + System.currentTimeMillis() + ".mp4")
                    item.file.copyTo(file, overwrite = true)
                    runCatching { item.file.delete() }
                    file
                }
                _result.value = dest
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: e.javaClass.simpleName
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun cancelGeneration() {
        cancelJob?.cancel()
        cancelJob = null
        _isGenerating.value = false
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

    private fun deleteReferenceFiles(paths: List<String>) {
        paths.forEach { runCatching { File(it).delete() } }
    }

    companion object {
        private const val MAX_REFERENCE_IMAGES = 4
    }
}
