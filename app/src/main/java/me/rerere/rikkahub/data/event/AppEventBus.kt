package me.rerere.rikkahub.data.event

/* ───【原版对齐】AppEventBus.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class AppEventBus {
    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<AppEvent> = _events.asSharedFlow()

    suspend fun emit(event: AppEvent) {
        _events.emit(event)
    }

    /**
     * 非挂起发送，缓冲满时丢弃事件并返回 false。
     * 用于高频且允许丢失的事件（如流式生成更新），避免反压发送方。
     */
    fun tryEmit(event: AppEvent): Boolean = _events.tryEmit(event)
}
