package me.rerere.rikkahub.data.ai.transformers

/* ───【原版对齐】DocumentAsPromptTransformer.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * v4.5.5: 上传模式分流 — classic=原版全量内联 (全文提取进 prompt) /
 *         compat=当前路径引用 (占位+workspace_read_file 按需读取)。
 *         客户端设置 (SettingClientPage) 决定, 请求体严格按所选模式构造。
 * ───────────────────────────────────────────────────────────────*/

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import java.io.File

object DocumentAsPromptTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return if (ctx.settings.fileUploadMode == "classic") {
            transformClassic(messages)
        } else {
            transformCompat(messages)
        }
    }

    /** classic (用户可选): 原版 2.5.1 全量内联 — 全文提取进 prompt, 无损直读 */
    private suspend fun transformClassic(messages: List<UIMessage>): List<UIMessage> =
        withContext(Dispatchers.IO) {
            messages.map { message ->
                message.copy(
                    parts = message.parts.toMutableList().apply {
                        val documents = filterIsInstance<UIMessagePart.Document>()
                        if (documents.isNotEmpty()) {
                            documents.forEach { document ->
                                val content = readDocumentContent(document)
                                val path = resolveWorkspacePath(document)
                                val pathAttr = path?.let { " path=\"" + it + "\"" } ?: ""
                                val prompt = "<UploadFile name=\"" + document.fileName + "\"" + pathAttr + ">\n" +
                                    "```\n" + content + "\n```\n" +
                                    "</UploadFile>"
                                add(0, UIMessagePart.Text(prompt))
                            }
                        }
                    }
                )
            }
        }

    /** compat (默认): 精确路径引用 — 请求短小、前缀稳定、完整内容经工具按需无损读取 */
    private fun transformCompat(messages: List<UIMessage>): List<UIMessage> =
        messages.map { message ->
            message.copy(
                parts = message.parts.toMutableList().apply {
                    val documents = filterIsInstance<UIMessagePart.Document>()
                    if (documents.isNotEmpty()) {
                        documents.forEach { document ->
                            val path = resolveWorkspacePath(document)
                                ?: "/upload/" + document.fileName
                            remove(document)
                            add(
                                0,
                                UIMessagePart.Text(
                                    "<UploadFile name=\"" + document.fileName + "\" path=\"" + path + "\">\n" +
                                        "[文件未内联到上下文。完整内容在工作区路径: " + path + " — 需要查看时调用 workspace_read_file 工具传入该路径]\n" +
                                        "</UploadFile>"
                                ),
                            )
                        }
                    }
                }
            )
        }

    private fun parsePdfAsText(file: File): String {
        return PdfParser.parserPdf(file)
    }

    private fun parseDocxAsText(file: File): String {
        return DocxParser.parse(file)
    }

    private fun parsePptxAsText(file: File): String {
        return PptxParser.parse(file)
    }

    private fun parseEpubAsText(file: File): String {
        return EpubParser.parse(file)
    }

    // 上传文件保存在 filesDir/upload 下, 该目录通过 proot 挂载到 workspace 的 /upload
    // 返回文件在 workspace 内的绝对路径, 便于 AI 用 workspace 工具直接读取原始文件
    private fun resolveWorkspacePath(document: UIMessagePart.Document): String? {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull() ?: return null
        if (file.parentFile?.name != "upload") return null
        return "/upload/" + file.name
    }

    private fun readDocumentContent(document: UIMessagePart.Document): String {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull()
            ?: return "[ERROR, invalid file uri: " + document.fileName + "]"
        if (!file.exists() || !file.isFile) {
            return "[ERROR, file not found: " + document.fileName + "]"
        }
        return runCatching {
            when (document.mime) {
                "application/pdf" -> parsePdfAsText(file)
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> parseDocxAsText(file)
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> parsePptxAsText(file)
                "application/epub+zip" -> parseEpubAsText(file)
                else -> file.readText()
            }
        }.getOrElse {
            "[ERROR, failed to read file: " + document.fileName + "]"
        }
    }
}
