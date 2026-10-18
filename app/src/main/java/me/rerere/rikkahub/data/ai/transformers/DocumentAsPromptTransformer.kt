/* 【域 B·AI 传输】 | 地图: docs/APP_MAP.md §B */
package me.rerere.rikkahub.data.ai.transformers

/* ───【原版对齐】DocumentAsPromptTransformer.kt | 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * v4.5.5: 上传模式分流 — classic=原版全量内联 (全文提取进 prompt) /
 *         compat=当前路径引用 (占位+workspace_read_file 按需读取)。
 *         客户端设置 (SettingClientPage) 决定, 请求体严格按所选模式构造。
 * v4.8.109: 兜底纪律 — Document part 绝不残留 (残留=出站空数组被网关拒收)。
 * v4.8.189 (L-A3 整段重写): 上传四元组收口 — 码/宿主文件/沙箱路径一律经
 *   UploadRef 单一解析器; 本文件不再自建解析 (旧 resolveUploadFile 已删)。
 * ───────────────────────────────────────────────────────────────*/

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import me.rerere.rikkahub.data.files.UploadRef
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
                                // v4.8.109: per-document 全兜底 — 任何读取/构建异常都必须完成
                                // "移除 Document part + 占位文本替换"；Document part 残留会在出站
                                // 构建器被忽略 → 消息 content 变空数组 → 网关拒绝
                                // ("Too small: expected array to have >=1 items" 的根源)。
                                val prompt = runCatching {
                                    val ref = UploadRef.resolveDocumentUrl(document.url)
                                    val content = readDocumentContent(ref?.hostFile, document)
                                    val attrs = buildString {
                                        append(" name=\"").append(document.fileName).append('"')
                                        ref?.let { append(" code=\"").append(it.code).append('"') }
                                        ref?.let { append(" path=\"").append(it.sandboxPath).append('"') }
                                    }
                                    "<UploadFile" + attrs + ">\n" +
                                        "```\n" + content + "\n```\n" +
                                        "</UploadFile>"
                                }.getOrElse { e ->
                                    "<UploadFile name=\"" + document.fileName + "\">\n" +
                                        "[附件读取失败: " + (e.message ?: e.javaClass.simpleName) +
                                        " — 该附件未内联；如需查看请让用户重新发送]\n</UploadFile>"
                                }
                                remove(document)
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
                          runCatching {
                            val ref = UploadRef.resolveDocumentUrl(document.url)
                            remove(document)
                            val attrs = buildString {
                                append(" name=\"").append(document.fileName).append('"')
                                ref?.let { append(" code=\"").append(it.code).append('"') }
                                ref?.let { append(" path=\"").append(it.sandboxPath).append('"') }
                            }
                            val body = buildString {
                                append("<UploadFile").append(attrs).append(">")
                                if (ref != null) {
                                    append("\n[文件未内联到上下文。完整内容在工作区路径: ").append(ref.sandboxPath)
                                    append(" — 需要查看时调用 workspace_read_file 工具传入该路径]")
                                    append("\n[上传码 ").append(ref.code)
                                    append("：之后任何时候都可用 upload_fetch(code) 直取该文件，无需翻找 /upload。]")
                                } else {
                                    // v4.8.90 修复: 旧实现在解析失败时回退到 "/upload/<原文件名>" —— 物理名其实是
                                    // 随机 ID, 该路径根本不存在, 模型越查越找不到。现在如实说明并给出补救路径。
                                    append("\n[该附件未能解析到工作区 /upload（历史或特殊来源的附件）。")
                                    append("如需读取请调用 upload_fetch（用上传码，或先向用户索取），或请用户重新发送该文件。]")
                                }
                                append("\n</UploadFile>")
                            }
                            add(0, UIMessagePart.Text(body))
                          }.onFailure { e ->
                              // v4.8.109: 兜底 — Document part 绝不残留（残留=出站空数组拒收）
                              remove(document)
                              add(0, UIMessagePart.Text(
                                  "<UploadFile name=\"" + document.fileName + "\">\n" +
                                      "[附件引用生成失败: " + (e.message ?: e.javaClass.simpleName) +
                                      " — 如需查看请让用户重新发送]\n</UploadFile>"
                              ))
                          }
                        }
                    }
                }
            )
        }

    // 上传文件保存在 filesDir/upload 下, 该目录通过 proot 挂载到 workspace 的 /upload。
    // 宿主文件一律经 UploadRef 解析 (v4.8.189 收口); 解析不到返回 null —— 调用方绝不编造路径。
    private fun readDocumentContent(file: File?, document: UIMessagePart.Document): String {
        if (file == null || !file.exists() || !file.isFile) {
            return "[ERROR, file not found: " + document.fileName + "]"
        }
        return runCatching {
            when (document.mime) {
                "application/pdf" -> PdfParser.parserPdf(file)
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DocxParser.parse(file)
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> PptxParser.parse(file)
                "application/epub+zip" -> EpubParser.parse(file)
                else -> {
                    // v4.8.109: 二进制表格（xlsx/xls）不 readText 乱码 — 给出明确指引
                    val lower = document.fileName.lowercase()
                    if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
                        "[xlsx 表格未内联（二进制表格不建议全量内联）。" +
                            "需要查看时让模型使用沙盒工具按路径读取该文件。]"
                    } else {
                        file.readText()
                    }
                }
            }
        }.getOrElse {
            "[ERROR, failed to read file: " + document.fileName + "]"
        }
    }
}
