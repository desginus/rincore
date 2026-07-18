package me.rerere.rikkahub.data.ai.tools.routing

import me.rerere.ai.core.Tool

enum class ToolDomain(
    val label: String,
    val triggerDescription: String,
    val matchKeywords: List<String>,
) {
    压缩("压缩", "压缩和解压文件",
        listOf("zip", "archive", "compress", "extract", "tar", "unzip")),
    搜索("搜索", "搜索互联网、查找信息、获取网络数据",
        listOf("search", "web search", "scrape")),
    日历("日历", "查看和创建日历事件、管理日程安排",
        listOf("calendar", "event", "appointment")),
    媒体("媒体", "控制媒体播放：播放、暂停、停止、跳转",
        listOf("play media", "pause", "stop", "seek", "media player", "media status")),
    代码("代码", "执行代码、运行脚本、处理数据计算",
        listOf("javascript", "execute code", "eval", "script")),
    定时("定时", "创建和管理定时任务、cron 作业",
        listOf("schedule job", "cron job", "trigger job")),
    设备("设备", "访问设备信息：电量、位置、通知、剪贴板、分享",
        listOf("battery", "location", "screen time", "toast", "notification", "clipboard", "share", "open file", "show image")),
    工作区("工作区", "执行 Shell 命令、管理沙箱环境中的文件",
        listOf("workspace", "shell", "linux", "rootfs")),
    浏览器("浏览器", "操控网页：导航浏览、点击按钮、填写表单、截图",
        listOf("browser", "navigate", "click", "screenshot", "fill form", "webbody", "playwright")),
    文件("文件", "读取、写入、列出、复制、移动、删除文件和目录",
        listOf("file", "directory", "folder", "download", "read file", "write file", "copy file", "move file", "delete file", "list file", "batch")),
    AI("AI", "管理子代理、记忆、对话历史、技能模块、询问用户",
        listOf("subagent", "skill", "memory", "conversation", "recent chat", "agent", "ask_user", "check_token"));

    companion object {
        fun classify(tool: Tool): ToolDomain? {
            val text = "${tool.name} ${tool.description}".lowercase()
            return entries.firstOrNull { domain ->
                domain.matchKeywords.any { keyword -> text.contains(keyword) }
            }
        }
    }
}
