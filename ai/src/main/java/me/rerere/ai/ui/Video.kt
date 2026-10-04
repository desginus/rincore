package me.rerere.ai.ui

import java.io.File

/**
 * 视频生成产出 (v4.8.103) — 供应商侧完成提交/轮询/下载后落盘的本地临时文件。
 * 视频体积大, 不走 base64 内联; 调用方负责搬运到应用目录并清理临时文件。
 */
data class VideoGenerationItem(
    val file: File,
    val mimeType: String = "video/mp4",
)
