package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * v4.8.137 回归测试（B158 根修）: 上传码/上传文件命名的格式对齐与同分钟唯一性。
 * 历史 bug: NEW_NAME 正则按"12-13 位"误写（实际文件名 10 位 = MMddHHmm+序号），
 * 扫描永远取不到 max → 同分钟所有同 basename 文件（工具图恒 image.png）全部拿
 * 01 号 → 同一文件被覆盖、多个工具结果指向同一张图（用户 100% 复现）。
 */
class UploadCodesTest {

    private fun tempDir(): File = Files.createTempDirectory("upload-codes-test").toFile()

    @Test
    fun codeForFileName_matchesActualFormat() {
        // 实际格式 = MMddHHmm(8) + 序号(2+) — 码即文件名前缀
        assertEquals("1015104801", UploadCodes.codeForFileName("1015104801_image.png"))
        assertEquals("1015104801", UploadCodes.codeForFileName("1015104801_报告.pdf"))
        assertEquals("10151048100", UploadCodes.codeForFileName("10151048100_x.png"))
    }

    @Test
    fun hasCodeFormat_acceptsTimeCodesAndLegacy() {
        assertTrue(UploadCodes.hasCodeFormat("1015104801"))     // 10 位时间码
        assertTrue(UploadCodes.hasCodeFormat("10151048100"))    // 11 位（序号 3 位）
        assertTrue(UploadCodes.hasCodeFormat("23456789"))       // 8 位历史码
    }

    @Test
    fun createUploadFile_sameMinuteSameBasename_neverCollides() {
        val dir = tempDir()
        val a = FileUtils.createUploadFile(dir, "image.png", null)
        val b = FileUtils.createUploadFile(dir, "image.png", null)
        val c = FileUtils.createUploadFile(dir, "image.png", null)
        // 三个独立文件 — 不再共用一个名字互相覆盖（核心回归断言）
        assertNotEquals(a.name, b.name)
        assertNotEquals(a.name, c.name)
        assertNotEquals(b.name, c.name)
        assertTrue(a.isFile && b.isFile && c.isFile)
        // 序号/前缀递增（跨分钟滚动也满足字典序）
        assertTrue(a.name < b.name)
        assertTrue(b.name < c.name)
    }
}
