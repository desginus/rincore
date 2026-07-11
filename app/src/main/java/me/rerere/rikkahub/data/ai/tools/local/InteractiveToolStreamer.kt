package me.rerere.rikkahub.data.ai.tools.local

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
