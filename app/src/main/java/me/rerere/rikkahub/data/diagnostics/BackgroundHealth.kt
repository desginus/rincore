package me.rerere.rikkahub.data.diagnostics

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import me.rerere.rikkahub.data.model.Assistant
import java.io.File

/**
 * v5.0.27 用户定案：聊天背景图「遗传数据」体检与清理。
 *
 * 背景动机（B203 最终指向）：
 *  输入栏 haze 的唯一采样源 = ChatPage 的 `AssistantBackground`。
 *  当 `assistant.background != null` 时，该层绘制的是**一张图片**；
 *  若这张图是**低分辨率**（被 Crop 放大到全屏），haze 在低采样率下会得到规则网格/条纹。
 *  备份包不含 `files/` 下的图片文件 → 测试版导入后加载失败回落到渐变（干净）；
 *  正式版图片仍在 → 真实渲染 → 条纹。这解释了「同备份、测试版正常、正式版坏」。
 *
 * 本工具扫描所有助手的背景图，给出「失效 / 低清 / 正常」判定，并可一键清理。
 */
object BackgroundHealth {

    enum class Verdict { MISSING, LOW_RES, OK, REMOTE, NONE }

    data class Entry(
        val assistantId: String,
        val assistantName: String,
        val raw: String?,
        val verdict: Verdict,
        val width: Int = 0,
        val height: Int = 0,
        val detail: String = "",
    )

    /** 判定阈值：宽或高小于该值视为低清（全屏 Crop 后必然放大 → haze 采样伪影） */
    private const val MIN_EDGE_PX = 1080

    fun scan(context: Context, assistants: List<Assistant>): List<Entry> = assistants.map { assistant ->
        val raw = assistant.background
        if (raw.isNullOrBlank()) {
            return@map Entry(assistant.id.toString(), assistant.name.ifBlank { "助手" }, raw, Verdict.NONE, detail = "未设置背景图")
        }
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        if (uri == null || (uri.scheme != null && uri.scheme != "file" && uri.scheme != "content")) {
            return@map Entry(assistant.id.toString(), assistant.name.ifBlank { "助手" }, raw, Verdict.REMOTE, detail = "网络背景（不参与本地体检）")
        }
        val file = uri.path?.let { File(it) }
        if (file == null || !file.exists() || file.length() <= 0L) {
            return@map Entry(assistant.id.toString(), assistant.name.ifBlank { "助手" }, raw, Verdict.MISSING, detail = "文件不存在: ${uri.path}")
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }
        val w = opts.outWidth
        val h = opts.outHeight
        val verdict = if (w <= 0 || h <= 0) Verdict.LOW_RES
        else if (w < MIN_EDGE_PX || h < MIN_EDGE_PX) Verdict.LOW_RES
        else Verdict.OK
        Entry(
            assistantId = assistant.id.toString(),
            assistantName = assistant.name.ifBlank { "助手" },
            raw = raw,
            verdict = verdict,
            width = w,
            height = h,
            detail = when (verdict) {
                Verdict.LOW_RES -> "低清 ${w}x$h（低于 ${MIN_EDGE_PX}px，全屏放大后会导致输入栏条纹）"
                else -> "正常 ${w}x$h"
            },
        )
    }

    /** 是否需要清理（仅处理本地文件：缺失或低清） */
    fun needsCleanup(entry: Entry): Boolean =
        entry.verdict == Verdict.MISSING || entry.verdict == Verdict.LOW_RES
}
