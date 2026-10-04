/* 【域 F·主题渲染】 — 文档渲染 | 地图: docs/APP_MAP.md §F */
package me.rerere.rikkahub.ui.components.render

/* ───【自研】OfficeCliHtmlRenderer.kt — officecli 真渲染接入 (v4.8.100 渲染缺口补齐)
 * 背景: 内建提取器是"近似排版" (PPT 背景/版式还原率极低, 用户实证需导入 WPS 才能看)。
 *       officecli (随 rin-tools 资产分发到每个工作区 /usr/local/bin, aarch64 原生)
 *       的 html 模式 = 真实渲染引擎 (背景/版式/表格/形状/图片/字体全保真, 矢量文本)。
 * 链路: 复制文件到工作区 .render/<ts>/ → 沙箱 exec `officecli view <f> html -o index.html`
 *      → 后处理 (移除 three CDN importmap; docx/xlsx 注入"整页适配"脚本) → HtmlPages 结果。
 * 失败 (rootfs 未装/officecli 缺失/渲染出错) 一律返回 null, 由调用方回落内建提取器。
 * ───────────────────────────────────────────────────────────────*/

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.io.File

object OfficeCliHtmlRenderer {

    private val SUPPORTED_EXTS = setOf("docx", "pptx", "xlsx")

    /**
     * 整页适配: docx/xlsx 是固定页宽版式, 在手机视口 (≈390px) 下会溢出为横向滚动。
     * 注入轻量脚本: 首帧/旋转后按"自然宽 vs 视口宽"设置 body zoom, 让整页适屏
     * (缩小看全貌, 双指放大看细节 — 与幻灯片同一交互心智)。内容本来就能放下时零动作。
     */
    private const val FIT_SCRIPT = "<script>(function(){function fit(){try{var b=document.body;if(!b)return;if(!b.dataset.rinNat){var z=b.style.zoom;b.style.zoom='';b.dataset.rinNat=String(Math.max(document.documentElement.scrollWidth,b.scrollWidth));b.style.zoom=z;}var nat=parseFloat(b.dataset.rinNat||'0');var vw=document.documentElement.clientWidth;if(nat>vw+4){b.style.zoom=(vw/nat).toFixed(4);}else{b.style.zoom='';}}catch(e){}}window.addEventListener('resize',function(){try{delete document.body.dataset.rinNat;}catch(e){}fit();});if(document.readyState==='complete'){setTimeout(fit,80);}else{window.addEventListener('load',function(){setTimeout(fit,80);});}})();</script>"

    /** 真渲染; 不可用返回 null (调用方回落)。渲染产物目录 = RenderResult.HtmlPages.workDir。 */
    suspend fun render(input: File, workDir: File, title: String): RenderResult? = withContext(Dispatchers.IO) {
        runCatching {
            val ext = input.name.substringAfterLast('.', "").lowercase()
            if (ext !in SUPPORTED_EXTS) return@runCatching null

            val koin = GlobalContext.get()
            val repo = koin.get<me.rerere.rikkahub.data.repository.WorkspaceRepository>()
            val manager = koin.get<me.rerere.workspace.WorkspaceManager>()

            val workspaces = repo.getAllWorkspaces()
            val ws = workspaces.firstOrNull { manager.hasRootfs(it.root) } ?: return@runCatching null

            // 工作区内临时渲染目录 (沙箱可见): <files>/.render/<stamp>/
            val filesRoot = manager.filesDir(ws.root).apply { mkdirs() }
            val renderRoot = File(filesRoot, ".render").apply { mkdirs() }
            val dir = File(renderRoot, System.currentTimeMillis().toString()).apply { mkdirs() }
            // 仅保留最近 3 个渲染目录 — 工作区不堆积
            runCatching {
                renderRoot.listFiles()?.sortedByDescending { it.name }?.drop(3)?.forEach { it.deleteRecursively() }
            }

            val safeName = input.name.replace("'", "_")
            val src = File(dir, safeName)
            input.inputStream().use { ins -> src.outputStream().use { outs -> ins.copyTo(outs) } }

            val rel = ".render/${dir.name}/$safeName"
            val outRel = ".render/${dir.name}/index.html"
            val cmd = "officecli view '/workspace/$rel' html -o '/workspace/$outRel'"
            val res = repo.executeCommand(ws.id, cmd, timeoutMillis = 180_000L)
            if (res.exitCode != 0) return@runCatching null

            val out = File(dir, "index.html")
            if (!out.isFile || out.length() <= 0L) return@runCatching null
            postProcess(out, ext)
            runCatching { src.delete() }

            RenderResult.HtmlPages(title = title, workDir = dir, pageCount = 1, canDark = false)
        }.getOrNull()
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
