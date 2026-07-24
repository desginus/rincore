package me.rerere.rikkahub.ui.pages.desk

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import java.io.File
import kotlin.uuid.Uuid

class DeskVM(
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
    private val conversationRepo: ConversationRepository,
) : ViewModel() {

    private val _filePath = MutableStateFlow<String?>(null)
    val filePath: StateFlow<String?> = _filePath.asStateFlow()
    private val _fileContent = MutableStateFlow("")
    val fileContent: StateFlow<String> = _fileContent.asStateFlow()
    private val _fileLanguage = MutableStateFlow("kotlin")
    val fileLanguage: StateFlow<String> = _fileLanguage.asStateFlow()

    private val _messages = MutableStateFlow<List<DeskMessage>>(emptyList())
    val messages: StateFlow<List<DeskMessage>> = _messages.asStateFlow()
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    val workspaceRoot = File(context.filesDir, "workspace")

    private var conversationId: Uuid? = null

    init {
        viewModelScope.launch { initConversation() }
    }

    private suspend fun initConversation() {
        val settingsData = settingsStore.settingsFlow.first()
        val assistant = settingsData.getCurrentAssistant()
        val model = settingsData.getCurrentChatModel() ?: run {
            _messages.value = _messages.value + DeskMessage(DeskMessageRole.SYSTEM, "⚠️ 未配置模型")
            return
        }
        val conv = Conversation(
            assistantId = assistant.id,
            title = "Desk 工作区",
            messageNodes = emptyList(),
        )
        conversationRepo.insertConversation(conv)
        chatService.initializeConversation(conv.id)
        conversationId = conv.id
        _messages.value = _messages.value + DeskMessage(
            DeskMessageRole.SYSTEM, "✅ Desk 就绪，模型: ${model.name}")
    }

    fun openFile(path: String) {
        val file = File(workspaceRoot, path)
        if (file.exists() && file.isFile) {
            _filePath.value = path
            _fileContent.value = file.readText()
            _fileLanguage.value = inferLanguage(path)
        }
    }

    fun updateContent(content: String) { _fileContent.value = content }

    fun sendMessage(text: String) {
        val cid = conversationId ?: run {
            _messages.value = _messages.value + DeskMessage(DeskMessageRole.SYSTEM, "⚠️ 对话未初始化")
            return
        }
        _messages.value = _messages.value + DeskMessage(DeskMessageRole.USER, text)
        val parts = buildList {
            _filePath.value?.let { p ->
                add(UIMessagePart.Text("[当前文件: $p]\n```${_fileLanguage.value}\n${_fileContent.value}\n```\n"))
            }
            add(UIMessagePart.Text(text))
        }
        viewModelScope.launch {
            _isRunning.value = true
            try { chatService.sendMessage(cid, parts, answer = true) }
            catch (e: Exception) {
                _messages.value = _messages.value + DeskMessage(DeskMessageRole.SYSTEM, "❌ ${e.message}")
            } finally { _isRunning.value = false }
        }
    }

    fun stopAi() {
        conversationId?.let { viewModelScope.launch { chatService.stopGeneration(it) } }
        _isRunning.value = false
    }

    private fun inferLanguage(path: String): String = when {
        path.endsWith(".kt") -> "kotlin"
        path.endsWith(".java") -> "java"
        path.endsWith(".py") -> "python"
        path.endsWith(".js") || path.endsWith(".ts") -> "javascript"
        path.endsWith(".json") -> "json"
        path.endsWith(".xml") || path.endsWith(".html") -> "xml"
        path.endsWith(".md") -> "markdown"
        else -> "text"
    }

    override fun onCleared() {
        super.onCleared()
        conversationId?.let { chatService.removeConversationReference(it) }
    }
}
