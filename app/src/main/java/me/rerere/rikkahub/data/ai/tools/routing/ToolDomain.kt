package me.rerere.rikkahub.data.ai.tools.routing

import me.rerere.ai.core.Tool

enum class ToolDomain(
    val label: String,
    val triggerDescription: String,
    val matchKeywords: List<String>,
) {
    工作区("工作区", "在Linux沙箱中执行shell命令、读写文件、编译运行代码。文件持久于/workspace",
        listOf("workspace", "shell", "linux", "rootfs")),
    搜索("搜索", "搜索互联网获取实时信息、查资料、查新闻",
        listOf("search", "web search", "scrape")),
    文件("文件", "读/写/列/复制/移动/删除文件和目录，批量操作、下载文件",
        listOf("file", "directory", "folder", "download", "read file", "write file", "copy file", "move file", "delete file", "list file", "batch")),
    压缩("压缩", "创建zip压缩包或解压提取",
        listOf("zip", "archive", "compress", "extract", "tar", "unzip")),
    日历("日历", "创建和查询日历事件、日程安排",
        listOf("calendar", "event", "appointment")),
    媒体("媒体", "控制音频播放/暂停/停止/跳转",
        listOf("play media", "pause", "stop", "seek", "media player", "media status")),
    代码("代码", "用JavaScript执行计算、处理数据、运行脚本",
        listOf("javascript", "execute code", "eval", "script")),
    定时("定时", "创建定时任务/cron作业、管理计划任务",
        listOf("schedule job", "cron job", "trigger job")),
    设备("设备", "获取设备状态(电量/定位/屏幕时间)、系统通知、剪贴板、分享",
        listOf("battery", "location", "screen time", "toast", "notification", "clipboard", "share", "open file", "show image")),
    浏览器("浏览器", "自动化操控网页：导航/点击/填表/截图",
        listOf("browser", "navigate", "click", "screenshot", "fill form", "webbody", "playwright")),
    AI("AI", "子代理派遣、记忆存取、对话历史搜索、技能加载、用户提问",
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
