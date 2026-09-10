package me.rerere.rikkahub.ui.hooks

/* ───【原版对齐】UseAssistant.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
fun rememberAssistantState(
    settings: Settings,
    onUpdateSettings: (Settings) -> Unit
): AssistantState {
    return remember(settings, onUpdateSettings) {
        AssistantState(settings, onUpdateSettings)
    }
}

class AssistantState(
    private val settings: Settings,
    private val onUpdateSettings: (Settings) -> Unit
) {
    private var _currentAssistant by mutableStateOf(
        settings.getCurrentAssistant()
    )
    val currentAssistant get() = _currentAssistant

    fun setSelectAssistant(assistant: Assistant) {
        onUpdateSettings(
            settings.copy(
                assistantId = assistant.id
            )
        )
    }
}
