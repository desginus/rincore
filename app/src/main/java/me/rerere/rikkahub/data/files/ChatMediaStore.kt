/* 【域 D·工作区沙箱】 — 文件/技能 | 地图: docs/APP_MAP.md §D */
package me.rerere.rikkahub.data.files

/* ───【自研】ChatMediaStore.kt — v4.8.185 对话媒体快照库
 * 用户定版 (2026-10-18): "把渲染图片改成渲染后保存的图片 —— 模型输出地址,
 * 渲染器渲染为图片, 然后将图片保存在对话中 (Base64 或 PNG/JPG 之类的),
 * Markdown 导出时也要把图片带走"。
 * 职责: 生成完成时把"地址引用"的图片按 **原格式字节复制** 进对话专属目录
 * (filesDir/chat-media/<conversationId>/), 消息内地址由固化器重写为快照地址;
 * 此后显示 (XSS 门/Coil 认领层) 与导出全链走既有 file:// 私有路径解析, 零新机制。
 * 生命周期: 对话删除时整目录清理 (deleteConversationMedia)。
 * ───────────────────────────────────────────────────────────────*/

import android.content.Context
import java.io.File

object ChatMediaStore {
    const val DIR_NAME = "chat-media"

    fun mediaRoot(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    fun conversationDir(context: Context, conversationId: String?): File =
        File(mediaRoot(context), conversationId?.takeIf { it.isNotBlank() } ?: "shared")
            .apply { if (!exists()) mkdirs() }

    /**
     * 是否为对话媒体快照地址 (规范化后含 /files/chat-media/) — 固化器幂等跳过判定。
     */
    fun isSnapshotUrl(url: String): Boolean {
        val t = url.trim()
        val decoded = runCatching {
            me.rerere.rikkahub.utils.normalizeDataUserAlias(
                if ('%' in t) me.rerere.rikkahub.utils.percentDecodeLenient(t) else t
            )
        }.getOrDefault(t)
        return decoded.contains("/files/$DIR_NAME/")
    }

    /**
     * 快照复制 — 原格式字节复制 (PNG/JPG 原样, 不重编码), 唯一文件名防撞号。
     * 返回 null = 失败 (调用方保留原地址, 下轮再试; 失败不阻断生成)。
     */
    fun snapshotCopy(context: Context, conversationId: String?, src: File): File? {
        return runCatching {
            if (!src.isFile || !src.canRead()) return@runCatching null
            val dir = conversationDir(context, conversationId)
            val base = src.nameWithoutExtension
                .replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
                .take(60)
                .ifBlank { "image" }
            val ext = src.extension.ifBlank { "png" }
            var out: File? = null
            var n = 1
            while (out == null && n <= 9999) {
                val candidate = File(dir, "${base}_$n.$ext")
                if (candidate.createNewFile()) {
                    src.inputStream().use { input ->
                        candidate.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (candidate.length() > 0) out = candidate else candidate.delete()
                } else {
                    n++
                }
            }
            out
        }.getOrNull()
    }

    /** 对话删除时整目录清理。 */
    fun deleteConversationMedia(context: Context, conversationId: String) {
        runCatching {
            val dir = File(File(context.filesDir, DIR_NAME), conversationId)
            if (dir.exists()) dir.deleteRecursively()
        }
    }
}
