package me.rerere.rikkahub.ui.components.render

import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * PDF 内容视图 (v3.9.8): 基于 AndroidPdfViewer (barteksc, GitHub 成熟库)
 * - 双指缩放 / 双击缩放 / 滑动翻页 / 页码显示 全部由库原生支持
 * - 高分辨率渲染, 无手动位图管线
 */
@Composable
fun PdfRenderView(
    pdfFile: File,
    onPageChange: (Int, Int) -> Unit,
) {
    val pageCountRef = remember { AtomicInteger(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                PDFView(context, null).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    fromFile(pdfFile)
                        .enableSwipe(true)
                        .swipeVertical(false)
                        .enableDoubletap(true)
                        .defaultPage(0)
                        .showPageNumber(false)
                        .enableAnnotationRendering(false)
                        .spacing(8)
                        .onPageChange(object : OnPageChangeListener {
                            override fun onPageChanged(page: Int, pageCount: Int) {
                                pageCountRef.set(pageCount)
                                onPageChange(page + 1, pageCount)
                            }
                        })
                        .load()
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}