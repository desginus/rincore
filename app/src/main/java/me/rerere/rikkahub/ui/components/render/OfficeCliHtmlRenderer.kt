/* 【域 F·主题渲染】 — 文档渲染 | 地图: docs/APP_MAP.md §F */
package me.rerere.rikkahub.ui.components.render

/* ───【自研】OfficeCliHtmlRenderer.kt — officecli 真渲染接入 (v4.8.102)
 * 背景: 内建提取器是"近似排版" (PPT 背景/版式还原率极低, 用户实证需导入 WPS 才能看)。
 *       officecli (随 rin-tools 资产分发到每个工作区 /usr/local/bin, aarch64 原生)
 *       的 html 模式 = 真实渲染引擎 (背景/版式/表格/形状/图片/字体全保真, 矢量文本)。
 * 链路: 复制文件到工作区 .render/ → 沙箱 exec `officecli view <f> html -o page1.html`
 *      → 后处理 (移除 three CDN importmap; docx/xlsx 注入"整页适配"脚本) → HtmlPages 结果。
 * 产物名必须是 pageN.html —— HtmlPages 消费契约 (预览器加载 File(workDir, "page${pageIndex+1}.html"))。
 * v4.8.102 提速: ①按 (路径+大小+mtime) 结果缓存 — 重复打开零沙箱直出;
 *   ②源文件已在工作区 files 区内时免拷贝直渲染; ③LRU 保留最近 KEEP_RENDER_DIRS 个渲染目录。
 * ───────────────────────────────────────────────────────────────*/

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.io.File
import java.security.MessageDigest

object OfficeCliHtmlRenderer {

    private val SUPPORTED_EXTS = setOf("docx", "pptx", "xlsx")

    /** 渲染目录 LRU 保留数量 (.render/cache-* 与历史目录一起计) */
    private const val KEEP_RENDER_DIRS = 8

    /**
     * 整页适配: docx/xlsx 是固定页宽版式, 在手机视口 (≈390px) 下会溢出为横向滚动。
     * 注入轻量脚本: 首帧/旋转后按"自然宽 vs 视口宽"设置 body zoom, 让整页适屏
     * (缩小看全貌, 双指放大看细节 — 与幻灯片同一交互心智)。内容本来就能放下时零动作。
     */
private const val FIT_SCRIPT = "<script>(function(){function fit(){try{var b=document.body;if(!b)return;if(!b.dataset.rinNat){var z=b.style.zoom;b.style.zoom='';b.dataset.rinNat=String(Math.max(document.documentElement.scrollWidth,b.scrollWidth));b.style.zoom=z;}var nat=parseFloat(b.dataset.rinNat||'0');var vw=document.documentElement.clientWidth;if(nat>vw+4){b.style.zoom=(vw/nat).toFixed(4);}else{b.style.zoom='';}}catch(e){}}window.addEventListener('resize',function(){try{delete document.body.dataset.rinNat;}catch(e){}fit();});if(document.readyState==='complete'){setTimeout(fit,80);}else{window.addEventListener('load',function(){setTimeout(fit,80);});}})();</script>"

    /** 真渲染; 不可用返回 null (调用方回落)。结果目录 = RenderResult.HtmlPages.workDir。 */
    suspend fun render(
        input: File,
        @Suppress("UNUSED_PARAMETER") workDir: File,
        title: String,
    ): RenderResult? = withContext(Dispatchers.IO) {
        runCatching {
            val ext = input.name.substringAfterLast('.', "").lowercase()
            if (ext !in SUPPORTED_EXTS) return@runCatching null

            val koin = GlobalContext.get()
            val repo = koin.get<me.rerere.rikkahub.data.repository.WorkspaceRepository>()
            val manager = koin.get<me.rerere.workspace.WorkspaceManager>()

            val ws = repo.getAllWorkspaces().firstOrNull { manager.hasRootfs(it.root) }
                ?: return@runCatching null

            val filesRoot = manager.filesDir(ws.root).apply { mkdirs() }
            val renderRoot = File(filesRoot, ".render").apply { mkdirs() }

            // ① 结果缓存: (路径+大小+mtime) 键 — 重复打开零沙箱直出
            val key = sha1(input.absolutePath + "|" + input.length() + "|" + input.lastModified())
            val dir = File(renderRoot, "cache-" + key)
            val out = File(dir, "page1.html")
            if (out.isFile && out.length() > 0L) {
                runCatching { dir.setLastModified(System.currentTimeMillis()) }
                return@runCatching RenderResult.HtmlPages(title, dir, 1, false)
            }

            dir.mkdirs()

            // ② 源文件已在工作区 files 区内 → 免拷贝直渲染; 区外文件 → 拷入缓存目录
            val filesRootPrefix = filesRoot.absolutePath + File.separator
            val srcSandbox: String = if (input.absolutePath.startsWith(filesRootPrefix)) {
                "/workspace/" + input.absolutePath.removePrefix(filesRootPrefix)
            } else {
                val safeName = input.name.replace("'", "_")
                input.inputStream().use { ins ->
                    File(dir, safeName).outputStream().use { outs -> ins.copyTo(outs) }
                }
                "/workspace/.render/cache-" + key + "/" + safeName
            }
            val outSandbox = "/workspace/.render/cache-" + key + "/page1.html"
            val cmd = "officecli view '" + srcSandbox + "' html -o '" + outSandbox + "'"
            val res = repo.executeCommand(ws.id, cmd, timeoutMillis = 180_000L)
            if (res.exitCode != 0 || !out.isFile || out.length() <= 0L) return@runCatching null

            postProcess(out, ext)

            // ③ LRU 清理 (保留最近 KEEP_RENDER_DIRS 个)
            runCatching {
                renderRoot.listFiles()
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(KEEP_RENDER_DIRS)
                    ?.forEach { it.deleteRecursively() }
            }

            RenderResult.HtmlPages(title, dir, 1, false)
        }.getOrNull()
    }

    private fun sha1(text: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun postProcess(htmlFile: File, ext: String) {
        val text = htmlFile.readText()
        var out = text
        // three.js importmap 走 CDN — 离线无意义且拖慢首帧; 移除
        out = out.replace(Regex("<script type=\"importmap\">.*?</script>"), "")
        // 固定页宽文档 (docx/xlsx): 注入整页适配; pptx 自带响应式缩放, 不动
        if (ext == "docx" || ext == "xlsx") {
            out = if (out.contains("</body>")) out.replace("</body>", FIT_SCRIPT + "</body>") else out + FIT_SCRIPT
        }
        if (out != text) htmlFile.writeText(out)
    }
}
