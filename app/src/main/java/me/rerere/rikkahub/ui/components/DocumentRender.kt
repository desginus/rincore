package me.rerere.rikkahub.ui.components

/**
 * 渲染类型判定 (v3.9.6)
 * 实际渲染由渲染机 DocumentRenderEngine 统一处理,
 * 此处仅保留胶囊窗卡片显示渲染选项的判定。
 */
enum class RenderKind { HTML, PDF, DOC, SHEET, SLIDES, IMAGE, VIDEO, AUDIO, TEXT, NONE }

fun detectRenderKind(fileName: String): RenderKind {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "html", "htm", "svg" -> RenderKind.HTML
        "pdf" -> RenderKind.PDF
        "docx" -> RenderKind.DOC
        "xlsx", "csv" -> RenderKind.SHEET
        "pptx" -> RenderKind.SLIDES
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "ico" -> RenderKind.IMAGE
        "mp4", "mkv", "webm", "3gp", "mov", "avi" -> RenderKind.VIDEO
        "mp3", "wav", "flac", "aac", "m4a", "ogg", "oga", "opus" -> RenderKind.AUDIO
        "txt", "md", "json", "log", "xml", "yaml", "yml", "toml", "ini",
        "py", "js", "kt", "java", "c", "cpp", "h", "sh", "sql", "css", "ts", "jsx" -> RenderKind.TEXT
        else -> RenderKind.NONE
    }
}
