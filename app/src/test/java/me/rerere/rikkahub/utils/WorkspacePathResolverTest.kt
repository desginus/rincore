package me.rerere.rikkahub.utils


/* ───【自研】WorkspacePathResolverTest.kt — R1-R13 规格单测 (渲染规格 §7)
 * 纯函数层: 前缀/解码/折叠/穿越拒绝; 管理器层: canonical 防逃逸 + 存在性。
 * ───────────────────────────────────────────────────────────────*/
import me.rerere.workspace.WorkspaceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspacePathResolverTest {

    // ── percentDecodeLenient ──

    @Test
    fun decode_percent20_space() {
        assertEquals("a b.png", percentDecodeLenient("a%20b.png"))
    }

    @Test
    fun decode_rawSpace_unchanged() {
        assertEquals("a b.png", percentDecodeLenient("a b.png"))
    }

    @Test
    fun decode_plus_isLiteral() {
        assertEquals("c+d.png", percentDecodeLenient("c+d.png"))
        assertEquals("c+d.png", percentDecodeLenient("c%2Bd.png"))
    }

    @Test
    fun decode_illegalPercent_keptAsIs() {
        assertEquals("x%2.png", percentDecodeLenient("x%2.png"))
        assertEquals("a%zz", percentDecodeLenient("a%zz"))
    }

    @Test
    fun decode_utf8_multiByte_chinese() {
        assertEquals("KEEP-交付区", percentDecodeLenient("KEEP-%E4%BA%A4%E4%BB%98%E5%8C%BA"))
    }

    // ── resolveWorkspaceRelPath ──

    @Test
    fun rel_basic_prefix() {
        assertEquals("/KEEP-交付区/mini.png", resolveWorkspaceRelPath("workspace://KEEP-交付区/mini.png"))
    }

    @Test
    fun rel_caseInsensitiveScheme() {
        assertEquals("/x/mini.png", resolveWorkspaceRelPath("WORKSPace://x/mini.png"))
    }

    @Test
    fun rel_fileWorkspaceAlias() {
        assertEquals("/x/y.png", resolveWorkspaceRelPath("file://workspace/x/y.png"))
    }

    @Test
    fun rel_bareMountPath() {
        assertEquals("/x/y.png", resolveWorkspaceRelPath("/workspace/x/y.png"))
    }

    @Test
    fun rel_encodedSpace() {
        assertEquals("/d/a b.png", resolveWorkspaceRelPath("workspace://d/a%20b.png"))
    }

    @Test
    fun rel_escapeDotDot_null() {
        assertNull(resolveWorkspaceRelPath("workspace://../etc/hosts"))
    }

    @Test
    fun rel_escapeNestedDotDot_null() {
        assertNull(resolveWorkspaceRelPath("workspace://KEEP-交付区/../../sdcard/x.png"))
    }

    @Test
    fun rel_dotDotInside_staysInRoot() {
        // 栈非空时 .. 弹栈但不出界 → 保留在 root 内
        assertEquals("/KEEP-交付区/y.png", resolveWorkspaceRelPath("workspace://KEEP-交付区/sub/../y.png"))
    }

    @Test
    fun rel_rootItself_null() {
        assertNull(resolveWorkspaceRelPath("workspace://"))
        assertNull(resolveWorkspaceRelPath("/workspace/"))
    }

    @Test
    fun rel_rfc8089ThreeSlash() {
        assertEquals("/x/y.png", resolveWorkspaceRelPath("file:///workspace/x/y.png"))
        assertEquals("/mini_t.png", resolveWorkspaceRelPath("file:///workspace/mini_t.png"))
    }

    @Test
    fun rel_rootLevelFile_viaWorkspaceScheme() {
        // v3.11.32 实测主用例: 根目录文件 workspace://rt0901.png → /workspace/rt0901.png
        assertEquals("/rt0901.png", resolveWorkspaceRelPath("workspace://rt0901.png"))
        assertEquals("/rt0901.png", resolveWorkspaceRelPath("/workspace/rt0901.png"))
    }

    @Test
    fun rel_wrongScheme_notRecognized() {
        assertNull(resolveWorkspaceRelPath("workspce://x.png"))
        assertNull(resolveWorkspaceRelPath("file:///sdcard/x.png"))
        assertNull(resolveWorkspaceRelPath("https://example.com/x.png"))
    }

    @Test
    fun isWorkspaceUri_prefixCaseInsensitive() {
        assertTrue(isWorkspaceUri("workspace://a.png"))
        assertTrue(isWorkspaceUri("WORKSPACE://a.png"))
        assertTrue(isWorkspaceUri("/workspace/a.png"))
        assertFalse(isWorkspaceUri("file:///sdcard/a.png"))
        assertFalse(isWorkspaceUri(null))
    }

    // ── WorkspaceManager.resolveRootfsFileSafe (canonical / 存在性 / 防逃逸) ──

    @Rule @JvmField
    val tmp = TemporaryFolder()

    private fun newManager(): Pair<WorkspaceManager, String> {
        val base = tmp.newFolder("base")
        val manager = WorkspaceManager(base)
        val root = "w1"
        manager.ensureWorkspace(root)
        return manager to root
    }

    @Test
    fun safe_resolve_existingFile() {
        val (manager, root) = newManager()
        val f = File(manager.filesDir(root), "KEEP-交付区")
        f.mkdirs()
        File(f, "mini.png").writeBytes(byteArrayOf(1, 2, 3))
        val resolved = manager.resolveRootfsFileSafe(root, "/workspace/KEEP-交付区/mini.png")
        assertTrue(resolved != null && resolved.isFile)
        assertEquals(3, resolved!!.length())
    }

    @Test
    fun safe_missingFile_null() {
        val (manager, root) = newManager()
        assertNull(manager.resolveRootfsFileSafe(root, "/workspace/no/such/file.png"))
    }

    @Test
    fun safe_directory_null() {
        val (manager, root) = newManager()
        File(manager.filesDir(root), "adir").mkdirs()
        assertNull(manager.resolveRootfsFileSafe(root, "/workspace/adir"))
    }

    @Test
    fun safe_escape_null() {
        val (manager, root) = newManager()
        val outside = File(tmp.root, "outside.png")
        outside.writeBytes(byteArrayOf(9))
        // cd.. 折叠逃出 filesDir → 拒绝
        assertNull(manager.resolveRootfsFileSafe(root, "/workspace/../outside.png"))
        assertTrue(outside.exists())
    }

    @Test
    fun safe_symlinkEscape_null() {
        val (manager, root) = newManager()
        val outside = File(tmp.root, "out2.png")
        outside.writeBytes(byteArrayOf(1))
        val link = File(manager.filesDir(root), "evil.png")
        val ok = runCatching {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        }.isSuccess
        if (!ok) return  // 平台不支持 symlink 时跳过
        assertNull(manager.resolveRootfsFileSafe(root, "/workspace/evil.png"))
    }

    // ── isAppPrivateFileUri (v4.8.113 渲染门: markdown file:// 私有目录放行) ──

    @Test
    fun appPrivate_workspaceRenderUrl_allowed() {
        assertTrue(
            isAppPrivateFileUri(
                "file:///data/data/me.rincore.app/files/workspaces/3f0be674-77cf-44aa-9efb-e9d3792b038f/files/KEEP-%E4%BA%A4%E4%BB%98%E5%8C%BA/mini.png",
                pkg = "me.rincore.app",
            )
        )
    }

    @Test
    fun appPrivate_dataUserAlias_allowed() {
        assertTrue(
            isAppPrivateFileUri(
                "file:///data/user/0/me.rincore.app/cache/shared_incoming/a.pdf",
                pkg = "me.rincore.app",
            )
        )
    }

    @Test
    fun appPrivate_otherPackage_rejected() {
        assertFalse(isAppPrivateFileUri("file:///data/data/com.other.app/files/a.png", pkg = "me.rincore.app"))
    }

    @Test
    fun appPrivate_publicStorage_and_nonFilesDir_rejected() {
        assertFalse(isAppPrivateFileUri("file:///sdcard/Download/a.png", pkg = "me.rincore.app"))
        assertFalse(isAppPrivateFileUri("file:///data/data/me.rincore.app/databases/x.db", pkg = "me.rincore.app"))
    }

    @Test
    fun appPrivate_nonFileScheme_rejected() {
        assertFalse(isAppPrivateFileUri("https://example.com/a.png", pkg = "me.rincore.app"))
    }

    // ── encodeMarkdownUrl + resolver 解码回环 (v4.8.113 render_markdown 不变量) ──

    @Test
    fun markdownUrl_encode_roundTrip_viaResolver() {
        val raw = "file:///data/data/me.rincore.app/files/workspaces/3f0be674-77cf-44aa-9efb-e9d3792b038f/files/KEEP-交付区/mini 1(2).png"
        val encoded = encodeMarkdownUrl(raw)
        assertFalse(encoded.contains(' '))
        assertFalse(encoded.contains('('))
        assertTrue(encoded.contains("KEEP-"))
        assertEquals(
            "/KEEP-交付区/mini 1(2).png",
            resolveWorkspaceRelPath(encoded),
        )
    }

    @Test
    fun safe_mtimeChanges_reflected() {
        val (manager, root) = newManager()
        val f = File(manager.filesDir(root), "mini_t.png")
        f.writeBytes(byteArrayOf(1))
        val r1 = manager.resolveRootfsFileSafe(root, "/workspace/mini_t.png")
        f.writeBytes(byteArrayOf(1, 2, 3, 4))
        val r2 = manager.resolveRootfsFileSafe(root, "/workspace/mini_t.png")
        assertEquals(r1!!.absolutePath, r2!!.absolutePath)
        assertEquals(4L, r2.length())
        assertTrue(r2.lastModified() >= 0)
    }
}
