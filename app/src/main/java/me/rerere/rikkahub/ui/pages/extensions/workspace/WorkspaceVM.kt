package me.rerere.rikkahub.ui.pages.extensions.workspace

/* ───【原版对齐】WorkspaceVM.kt | 差异 ±4 行 (基线 2.5.1)
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository

class WorkspaceVM(
    private val repository: WorkspaceRepository,
) : ViewModel() {
    val workspaces = repository.listFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun create(name: String) {
        viewModelScope.launch {
            runCatching { repository.create(name) }
        }
    }

    fun rename(workspace: WorkspaceEntity, name: String) {
        viewModelScope.launch {
            runCatching { repository.rename(workspace.id, name) }
        }
    }

    fun delete(workspace: WorkspaceEntity) {
        viewModelScope.launch {
            repository.delete(workspace.id)
        }
    }
}
