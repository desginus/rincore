package me.rerere.rikkahub.data.ai.transformers

/* ───【原版对齐】DocumentAsPromptTransformer.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import androidx.core.net.toFile
import androidx.core.net.toUri
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.io.File

object DocumentAsPromptTransformer : InputMessageTransformer {
    /**
     * v4.3.14 (BUG20 终版, 用户定版): 文档不再内联任何内容 — 上下文是缓存的
     * 基本盘, 大文件全文进 prompt 曾把单请求推到 6M tokens 且内容随截断边界
     * 漂移破坏缓存前缀。定版方案: Document 一律替换为精确的 workspace 路径
     * 引用 (filesDir/upload 经 proot 挂载为 /upload), 模型需要内容时用
     * read_file 工具按需读取 — 请求短小、前缀稳定、完整内容无损可取。
     */
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages.map { message ->
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
    }

    // 上传文件保存在 filesDir/upload 下, 该目录通过 proot 挂载到 workspace 的 /upload
    // 返回文件在 workspace 内的绝对路径, 便于 AI 用 workspace 工具直接读取原始文件
    private fun resolveWorkspacePath(document: UIMessagePart.Document): String? {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull() ?: return null
        if (file.parentFile?.name != "upload") return null
        return "/upload/" + file.name
    }
}
