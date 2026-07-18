package me.rerere.rikkahub.data.ai.tools.routing

import me.rerere.ai.core.Tool

/**
 * 工具域定义 — 按功能语义分类。
 *
 * 分类策略：扫描 Tool 的 name + description 文本，匹配关键词。
 * 声明顺序决定优先级（先声明先匹配），避免歧义。
 * 例如 ARCHIVE 在 FILE 之前，确保 zip_files 被归入 ARCHIVE 而非 FILE。
 *
 * 未匹配任何域的工具归入 "uncategorized"。
 * MCP 工具按服务器名分组（mcp:serverName），不使用此枚举。
 * Skill 工具统一归入 "skills" 域。
 */
enum class ToolDomain(
    val label: String,
    val triggerDescription: String,
    val matchKeywords: List<String>,
) {
    ARCHIVE(
        "archive",
        "compress files into zip/tar, extract archives, list archive contents",
        listOf("zip", "archive", "compress", "extract", "tar", "unzip")
    ),
    WEB(
        "web",
        "search the internet, look up facts/news, fetch a web page, scrape content",
        listOf("search the web", "scrape", "fetch page", "web search", "search_web")
    ),
    BROWSER(
        "browser",
        "navigate a website, click buttons, fill forms, take screenshots",
        listOf("browser", "navigate", "click", "screenshot", "fill form", "webbody", "playwright")
    ),
    CALENDAR(
        "calendar",
        "check calendar events, create appointments, schedule meetings",
        listOf("calendar", "event", "appointment")
    ),
    MEDIA(
        "media",
        "play audio, pause music, stop playback, seek, control media player",
        listOf("play media", "stop media", "pause media", "seek media", "resume media", "media player", "media status")
    ),
    CODE(
        "code",
        "run JavaScript calculations, execute code, process data programmatically",
        listOf("javascript", "execute code", "eval", "script")
    ),
    SCHEDULER(
        "scheduler",
        "create cron jobs, schedule recurring tasks, manage timers",
        listOf("schedule job", "cron job", "trigger job", "scheduled task",
            "list_jobs", "delete_job", "pause_job", "resume_job", "get_job_history")
    ),
    DEVICE(
        "device",
        "check battery, get location, view screen time, send notifications, copy to clipboard, share content, open files, show images",
        listOf("battery", "location", "screen time", "toast", "notification",
            "clipboard", "share", "open file", "show image", "scan media", "show_location")
    ),
    WORKSPACE(
        "workspace",
        "run shell commands, manage files in a Linux sandbox, work with project directories",
        listOf("workspace", "shell", "linux", "rootfs")
    ),
    FILE(
        "file",
        "list files, read files, write files, copy/move/delete files, create directories, batch operations",
        listOf("file", "directory", "folder", "download file", "read file", "write file",
            "copy file", "move file", "delete file", "list file", "create director", "batch")
    ),
    AI(
        "ai",
        "dispatch sub-agents, manage memory, search conversation history, list recent chats, ask user questions",
        listOf("subagent", "memory", "conversation", "recent chat", "agent",
            "ask_user", "check_token")
    );

    companion object {
        /**
         * 按功能语义分类工具。
         * @return 匹配的域，未匹配返回 null
         */
        fun classify(tool: Tool): ToolDomain? {
            val text = "${tool.name} ${tool.description}".lowercase()
            return entries.firstOrNull { domain ->
                domain.matchKeywords.any { keyword -> text.contains(keyword) }
            }
        }
    }
}
