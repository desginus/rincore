package me.rerere.rikkahub.data.ai.tools.local


/* ───【自研】InteractiveToolStreamer.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext

/**
 * Side-effect surface for interactive tools. The media player tool calls
 * `streamIfHeadless` after playing, so headless runs (cron/telegram) can
 * send a screenshot back to the originating chat. Default [NoOp] when no
 * streaming surface is wired.
 */
interface InteractiveToolStreamer {
    suspend fun streamIfHeadless(
        invocationContext: ToolInvocationContext?,
        actionLabel: String,
    )

    object NoOp : InteractiveToolStreamer {
        override suspend fun streamIfHeadless(
            invocationContext: ToolInvocationContext?,
            actionLabel: String,
        ) = Unit
    }
}
