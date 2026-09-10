package me.rerere.rikkahub.utils

/* ───【原版对齐】DatabaseUtil.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import android.database.CursorWindow
import android.util.Log

private const val TAG = "DatabaseUtil"

object DatabaseUtil {
    fun setCursorWindowSize(size: Int) {
        try {
            val field = CursorWindow::class.java.getDeclaredField("sCursorWindowSize")
            field.isAccessible = true
            val oldValue = field.get(null) as Int
            field.set(null, size)
            Log.i(TAG, "setCursorWindowSize: set $oldValue to $size")
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // 已fork io.requery.android.database 修改了window size，避免无法反射修改final字段
//        try {
//            val field =
//                io.requery.android.database.CursorWindow::class.java.getDeclaredField("sDefaultCursorWindowSize")
//            field.isAccessible = true
//            val oldValue = field.get(null) as Int
//            field.set(null, size)
//            Log.i(TAG, "setCursorWindowSize: set $oldValue to $size")
//        } catch (e: Exception) {
//            e.printStackTrace()
//        }
    }
}
