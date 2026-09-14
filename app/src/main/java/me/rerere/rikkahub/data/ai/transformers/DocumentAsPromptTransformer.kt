package me.rerere.rikkahub.data.ai.transformers

/* ───【原版对齐】DocumentAsPromptTransformer.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
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
        return withContext(Dispatchers.IO) {
            messages.map { message ->
                message.copy(
                    parts = message.parts.toMutableList().apply {
                        val documents = filterIsInstance<UIMessagePart.Document>()
                        if (documents.isNotEmpty()) {
                            documents.forEach { document ->
                                val content = readDocumentContent(document)
                                val path = resolveWorkspacePath(document)
                                val pathAttr = path?.let { " path=\"$it\"" } ?: ""
                                val prompt = """
                                  <UploadFile name="${document.fileName}"$pathAttr>
                                  ```
                                  $content
                                  ```
                                  </UploadFile>
                                  """.trimMargin()
                                add(0, UIMessagePart.Text(prompt))
                            }
                        }
                    }
                )
            }
        }
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
        return "/upload/${file.name}"
    }

    /**
     * v4.3.13 (BUG20): 文件内容上限防线 — 对话框直发的大文件全文进 prompt
     * 曾把单条请求推到 6M tokens (GLM 1M 上限的 6 倍), 上游 4xx 拒收后
     * pre-data 重试链反复重发同一巨型请求, 用户感知"彻底卡死"。
     * 超限策略: 保头 380K + 保尾 20K, 中间标注省略量与完整文件路径 —
     * 文件物理存于 filesDir/upload (proot 挂载为 /upload), 模型可用
     * read_file 工具按需读取完整内容, 与图片预算的 read_image 闭环同构。
     */
    private const val MAX_DOC_CHARS = 400_000
    private const val HEAD_KEEP = 380_000
    private const val TAIL_KEEP = 20_000

    private fun readDocumentContent(document: UIMessagePart.Document): String {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull()
            ?: return "[ERROR, invalid file uri: ${document.fileName}]"
        if (!file.exists() || !file.isFile) {
            return "[ERROR, file not found: ${document.fileName}]"
        }
        return runCatching {
            val raw = when (document.mime) {
                "application/pdf" -> parsePdfAsText(file)
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> parseDocxAsText(file)
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> parsePptxAsText(file)
                "application/epub+zip" -> parseEpubAsText(file)
                else -> file.readText()
            }
            if (raw.length <= MAX_DOC_CHARS) raw else {
                val head = raw.take(HEAD_KEEP)
                val tail = raw.takeLast(TAIL_KEEP)
                val omitted = raw.length - HEAD_KEEP - TAIL_KEEP
                "$head\n[... 文件过大，中间省略 ${'$'}{omitted} 字符；完整文件在工作区路径: ${'$'}{resolveWorkspacePath(document) ?: "/upload/" + document.fileName}，需要完整内容时调用 read_file 工具传入该路径 ...]\n$tail"
            }
        }.getOrElse {
            "[ERROR, failed to read file: ${document.fileName}]"
        }
    }
}
