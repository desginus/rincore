package me.rerere.rikkahub.utils

/* ───【原版对齐】UiState.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

sealed class UiState<out T> {
    object Idle : UiState<Nothing>()
    object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val error: Throwable) : UiState<Nothing>()
}

inline fun <T> UiState<T>.onSuccess(action: (T) -> Unit): UiState<T> {
    if (this is UiState.Success) {
        action(data)
    }
    return this
}

inline fun <T> UiState<T>.onError(action: (Throwable) -> Unit): UiState<T> {
    if (this is UiState.Error) {
        action(error)
    }
    return this
}

inline fun <T> UiState<T>.onLoading(action: () -> Unit): UiState<T> {
    if (this is UiState.Loading) {
        action()
    }
    return this
}
