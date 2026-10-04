/* 【域 A·对话核心】 — 页面 | 地图: docs/APP_MAP.md §A */
package me.rerere.rikkahub.ui.pages.videogen

/* ───【自研】VideoGenVM.kt — 视频生成 (v4.8.102)
 * 用户定版: 不走"模型类型"系统化配置 — 接口 URL / 模型 ID / 密钥全部在视频生成页内
 * 编辑; 两个内置适配走真实协议 (VideoGenEngine):
 *   · Google Veo (Gemini API: predictLongRunning → 轮询 → 下载)
 *   · 阿里云 HappyHorse (百炼 DashScope 异步任务: video-synthesis → tasks 轮询 → 下载)
 * UI 逻辑对齐图像生成页: 提示词 / 参考图(首帧) / 接口选择 / 生成设置 / 生成·取消 / 会话重置。
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
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.videogen.VideoGenDefaults
import me.rerere.rikkahub.data.videogen.VideoGenEndpoint
import me.rerere.rikkahub.data.videogen.VideoGenEngine
import okhttp3.OkHttpClient
import java.io.File

class VideoGenVM(
    context: Application,
    private val settingsStore: SettingsStore,
    private val okHttpClient: OkHttpClient,
) : AndroidViewModel(context) {

    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId

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

    private val _status = MutableStateFlow<VideoGenEngine.Status?>(null)
    val status: StateFlow<VideoGenEngine.Status?> = _status

    /** 错误文本 (提供方可读消息; "no_model"/"not_configured" 为哨兵值)。 */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _result = MutableStateFlow<File?>(null)
    val result: StateFlow<File?> = _result

    private val _savedToWorkspace = MutableStateFlow(false)
    val savedToWorkspace: StateFlow<Boolean> = _savedToWorkspace

    private var cancelJob: Job? = null

    /** 接口列表 (出厂 Veo + HappyHorse; 用户可编辑 URL/模型/密钥)。 */
    val endpoints: StateFlow<List<VideoGenEndpoint>> = settingsStore.settingsFlow
        .map { it.videoGenEndpoints }
        .stateIn(viewModelScope, SharingStarted.Eagerly, VideoGenDefaults.endpoints)

    fun updatePrompt(text: String) { _prompt.value = text }
    fun selectEndpoint(id: String) { _selectedId.value = id }
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

    /** 编辑接口配置 (URL / 模型 ID / 密钥) — 唯一写点。 */
    fun updateEndpoint(id: String, baseUrl: String, modelId: String, apiKey: String) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    videoGenEndpoints = settings.videoGenEndpoints.map { endpoint ->
                        if (endpoint.id == id) {
                            endpoint.copy(
                                baseUrl = baseUrl.trim(),
                                modelId = modelId.trim(),
                                apiKey = apiKey.trim(),
                            )
                        } else endpoint
                    },
                )
            }
        }
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
        _status.value = null
        _savedToWorkspace.value = false
    }

    fun generate() {
        val promptText = _prompt.value.trim()
        if (promptText.isEmpty()) return
        val list = endpoints.value
        val endpoint = list.firstOrNull { it.id == _selectedId.value } ?: list.firstOrNull()
        if (endpoint == null) {
            _error.value = "no_model"
            return
        }
        if (!endpoint.configured) {
            _error.value = "not_configured"
            return
        }
        val firstFrame = _referenceImages.value.firstOrNull()
        if (firstFrame != null && !endpoint.supportsFirstFrame) {
            _error.value = "该接口暂不支持参考图（HappyHorse 文生视频为纯文本输入）"
            return
        }
        cancelJob?.cancel()
        cancelJob = viewModelScope.launch {
            _isGenerating.value = true
            _error.value = null
            _result.value = null
            _savedToWorkspace.value = false
            _status.value = VideoGenEngine.Status.SUBMITTING
            try {
                val dir = File(getApplication<Application>().filesDir, "videogen").apply { mkdirs() }
                val dest = File(dir, "video_" + System.currentTimeMillis() + ".mp4")
                VideoGenEngine.generate(
                    client = okHttpClient,
                    endpoint = endpoint,
                    prompt = promptText,
                    aspectRatio = _aspect.value,
                    resolution = _resolution.value,
                    durationSeconds = _duration.value,
                    watermark = _watermark.value,
                    firstFramePath = firstFrame,
                    onStatus = { status -> _status.value = status },
                    destFile = dest,
                )
                _result.value = dest
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: e.javaClass.simpleName
            } finally {
                _isGenerating.value = false
                _status.value = null
            }
        }
    }

    fun cancelGeneration() {
        cancelJob?.cancel()
        cancelJob = null
        _isGenerating.value = false
        _status.value = null
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
