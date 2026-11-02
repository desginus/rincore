package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.model.groupByDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRepositoryTest {
    // v5.3.10：MemoryRepository 自 v5.3.7 起是（统一表 DAO + 旧条目式 DAO）双参构造；
    // 测试只覆盖统一表语义，legacy DAO 用最小替身（真调用会抛 NotImplementedError）。
    private val dao = FakeMemoryDAO()
    private val repository = MemoryRepository(dao)
    private val assistant = "assistant"

    private suspend fun create(path: String, content: String): MemoryFile {
        val result = repository.writeFile(assistant, path, content, MemoryRepository.NEW_FILE_VERSION)
        return (result as MemoryWriteResult.Success).file!!
    }

    @Test
    fun `creating a file that already exists returns the current content`() = runBlocking {
        val file = create("/topics/food.md", "- [stated] Likes noodles\n")

        val result = repository.writeFile(assistant, "/topics/food.md", "other", MemoryRepository.NEW_FILE_VERSION)

        assertEquals(MemoryWriteResult.Conflict(file), result)
        assertEquals(file.content, repository.getFile(assistant, "/topics/food.md")?.content)
    }

    @Test
    fun `writing with a stale version is rejected and a fresh version succeeds`() = runBlocking {
        val first = create("/profile.md", "- [stated] Lives in Shenzhen\n")
        val second = (repository.appendToFile(assistant, "/profile.md", "- [stated] Works as a designer", first.version)
            as MemoryWriteResult.Success).file!!

        val stale = repository.appendToFile(assistant, "/profile.md", "- [stated] Stale", first.version)
        assertEquals(MemoryWriteResult.Conflict(second), stale)

        val fresh = repository.writeFile(assistant, "/profile.md", "rewritten", second.version)
        assertEquals("rewritten", (fresh as MemoryWriteResult.Success).file?.content)
    }

    @Test
    fun `editing a missing file reports that it does not exist`() = runBlocking {
        val result = repository.appendToFile(assistant, "/topics/none.md", "line", "abc")

        assertEquals(MemoryWriteResult.Conflict(null), result)
    }

    @Test
    fun `append adds a line break when the file does not end with one`() = runBlocking {
        val file = create("/topics/food.md", "- first")

        val result = repository.appendToFile(assistant, "/topics/food.md", "- second\n", file.version)

        assertEquals("- first\n- second\n", (result as MemoryWriteResult.Success).file?.content)
    }

    @Test
    fun `replacing a whole line with nothing removes the line`() = runBlocking {
        val file = create("/topics/food.md", "- keep one\n- forget me\n- keep two\n")

        val result = repository.replaceInFile(assistant, "/topics/food.md", "- forget me", "", file.version)

        assertEquals("- keep one\n- keep two\n", (result as MemoryWriteResult.Success).file?.content)
    }

    @Test
    fun `replacing part of a line keeps the rest of the line`() = runBlocking {
        val file = create("/topics/work.md", "- [stated] On the search team\n")

        val result = repository.replaceInFile(
            assistant, "/topics/work.md", "On the search team", "On the infra team (previously on search)", file.version,
        )

        assertEquals(
            "- [stated] On the infra team (previously on search)\n",
            (result as MemoryWriteResult.Success).file?.content,
        )
    }

    @Test
    fun `replace is rejected when old_str matches nothing or several places`() = runBlocking {
        val file = create("/topics/food.md", "- tea\n- tea\n")

        val several = repository.replaceInFile(assistant, "/topics/food.md", "- tea", "- coffee", file.version)
        val none = repository.replaceInFile(assistant, "/topics/food.md", "- milk", "- coffee", file.version)

        assertTrue(several is MemoryWriteResult.Rejected)
        assertTrue(none is MemoryWriteResult.Rejected)
        assertEquals(file.content, repository.getFile(assistant, "/topics/food.md")?.content)
    }

    @Test
    fun `delete checks the version and removes the file`() = runBlocking {
        val file = create("/people/mom.md", "- [stated] Lives in Chengdu\n")

        assertEquals(MemoryWriteResult.Conflict(file), repository.deleteFile(assistant, "/people/mom.md", "stale"))
        assertEquals(MemoryWriteResult.Success(null), repository.deleteFile(assistant, "/people/mom.md", file.version))
        assertNull(repository.getFile(assistant, "/people/mom.md"))
    }

    @Test
    fun `oversized content and invalid paths are rejected`() = runBlocking {
        val tooLarge = repository.writeFile(assistant, "/topics/big.md", "a".repeat(MemoryFile.MAX_BYTES + 1))
        val notMarkdown = repository.writeFile(assistant, "/topics/food.txt", "content")
        val traversal = repository.writeFile(assistant, "/topics/../profile.md", "content")

        assertTrue(tooLarge is MemoryWriteResult.Rejected)
        assertTrue(notMarkdown is MemoryWriteResult.Rejected)
        assertTrue(traversal is MemoryWriteResult.Rejected)
        assertTrue(repository.getFiles(assistant).isEmpty())
    }

    @Test
    fun `paths without a leading slash are normalized`() = runBlocking {
        create("topics/food.md", "content")

        assertEquals(listOf("/topics/food.md"), repository.getFiles(assistant).map { it.path })
        assertEquals("content", repository.getFile(assistant, "topics/food.md")?.content)
    }

    @Test
    fun `memory stores are isolated and can be copied`() = runBlocking {
        create("/profile.md", "- [stated] Name is Lin\n")

        assertTrue(repository.getFiles("other").isEmpty())
        repository.copyFiles(assistant, "other")
        assertEquals("- [stated] Name is Lin\n", repository.getFile("other", "/profile.md")?.content)

        repository.deleteFilesOfAssistant(assistant)
        assertTrue(repository.getFiles(assistant).isEmpty())
        assertEquals(1, repository.getFiles("other").size)
    }

    @Test
    fun `description and aliases come from the frontmatter`() = runBlocking {
        val file = create(
            "/people/mom.md",
            """
                ---
                name: mom
                description: The user's mother and her move to Chengdu
                aliases: [妈妈, mother]
                ---

                - [stated] Lives in Chengdu
            """.trimIndent(),
        )

        assertEquals("The user's mother and her move to Chengdu", file.description)
        assertEquals(listOf("妈妈", "mother"), file.aliases)
    }

    @Test
    fun `files are grouped by directory with profile and preferences first`() = runBlocking {
        listOf(
            "/people/mom.md", "/topics/food.md", "/work/notes.md", "/preferences.md",
            "/areas/house-hunt.md", "/profile.md", "/topics/books.md",
        ).forEach { create(it, "content") }

        val groups = repository.getFiles(assistant).groupByDirectory()

        assertEquals(
            listOf(
                "/" to listOf("profile", "preferences"),
                "/topics" to listOf("books", "food"),
                "/areas" to listOf("house-hunt"),
                "/people" to listOf("mom"),
                "/work" to listOf("notes"),
            ),
            groups.map { (directory, files) -> directory to files.map { it.name } },
        )
    }

    @Test
    fun `titles are derived from the file name`() = runBlocking {
        assertEquals("Frontend Tooling", create("/topics/frontend-tooling.md", "content").title)
        assertEquals("Profile", create("/profile.md", "content").title)
        assertEquals("妈妈", create("/people/妈妈.md", "content").title)
    }

    @Test
    fun `a title in the frontmatter takes precedence over the file name`() = runBlocking {
        val food = create("/topics/food.md", "---\nname: food\ntitle: 饮食\n---\n\n- [stated] Likes noodles\n")
        assertEquals("饮食", food.title)
        assertEquals("Books", create("/topics/books.md", "---\nname: books\ntitle: \"\"\n---\n").title)
    }

    @Test
    fun `the stated marker is hidden from the displayed body`() = runBlocking {
        val file = create(
            "/topics/work.md",
            """
                ---
                name: work
                ---

                ## Work

                - [stated] On the infra team (previously on search)
                - [Stated] Works with [[alex]]
                - A line without the marker
            """.trimIndent(),
        )

        assertEquals(
            """
                ## Work

                - On the infra team (previously on search)
                - Works with [[alex]]
                - A line without the marker
            """.trimIndent(),
            file.displayBody.trim(),
        )
    }

    // ═══ v5.3.12 回归护栏：软删必须「读侧消失 + 写入不复活」 ═══
    //
    // 这条测试锁定的正是用户实测的「做梦整理的记忆无法被删除」：
    // 旧 DAO 的 getFilesOfAssistantFlow/getFilesOfAssistant 不过滤 invalidated_at，
    // 导致 invalidateFile 写入了失效标记、列表照常显示该条 = 删不掉。
    @Test
    fun `deleting a dream memory hides it from reads and later writes do not resurrect it`() = runBlocking {
        val path = "/topics/dream-delete.md"
        repository.writeFile(
            assistant,
            path,
            "【做梦主题】\n- [stated] 内容",
            MemoryRepository.NEW_FILE_VERSION,
            source = "dream",
        )
        assertTrue(repository.getFiles(assistant).any { it.path == path })

        assertTrue(repository.invalidateFile(assistant, path))
        assertTrue(
            "删除后列表必须立刻看不到该条",
            repository.getFiles(assistant).none { it.path == path },
        )

        // 模型或做梦整理再写同一路径：不得复活（用户已明确删除）
        repository.writeFile(assistant, path, "【做梦主题】\n- [stated] 新内容", source = "dream")
        assertTrue(
            "已删除的记忆不应被后续写入复活",
            repository.getFiles(assistant).none { it.path == path },
        )
    }

    @Test
    fun `invalidating a missing path reports false instead of pretending success`() = runBlocking {
        assertEquals(false, repository.invalidateFile(assistant, "/topics/never-existed.md"))
    }

    // ═══ v5.3.14 回归护栏：「以前的记忆删不掉」 ═══
    //
    // v5.3.7 的迁移写入的 path **没有前导 "/"**（"旧标题.md"），而 normalizePath 会补成
    // "/旧标题.md" → 精确匹配必然 0 行 → 老记忆改不了也删不掉（用户实测）。
    // 修复：invalidateFile 走多形态候选（原样/有无斜杠/规范化），无论 DB 里哪种写法都能命中。
    @Test
    fun `legacy path without leading slash can still be invalidated`() = runBlocking {
        // 直接按迁移产物形态入库（不带前导 /）
        (dao as FakeMemoryDAO).upsertFile(
            me.rerere.rikkahub.data.db.entity.MemoryFileEntity(
                assistantId = assistant,
                path = "旧标题.md",
                content = "【旧标题】\n- 老记忆",
                updatedAt = 1L,
                source = "active",
            )
        )
        assertEquals(1, repository.getFiles(assistant).size)

        assertTrue("无前导 / 的历史 path 也必须能删", repository.invalidateFile(assistant, "旧标题.md"))
        assertTrue(repository.getFiles(assistant).none { it.path == "旧标题.md" })
    }

    @Test
    fun `invalidation matches regardless of slashes either way`() = runBlocking {
        repository.writeFile(assistant, "/topics/a.md", "body", MemoryRepository.NEW_FILE_VERSION)
        // 传不带斜杠的形态同样要命中
        assertTrue(repository.invalidateFile(assistant, "topics/a.md"))
        assertTrue(repository.getFiles(assistant).none { it.path == "/topics/a.md" })
    }

    // ══════════════════════════════════════════════════════════════════════
    // v5.3.19 ★★记忆系统全结构体检后的回归护栏（6 条）
    //
    // 这六条锁定的都是「旧实现会静默做错事」的路径 —— 它们不报错、不崩溃，
    // 只是让用户看到「记忆重复 / 改不动 / 越权看到别人的记忆」，因此只能靠测试发现。
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun seedLegacy(path: String, content: String, folderId: String? = null, conversationId: String? = null) {
        dao.upsertFile(
            me.rerere.rikkahub.data.db.entity.MemoryFileEntity(
                assistantId = assistant,
                path = path,
                content = content,
                updatedAt = 1L,
                source = "active",
                folderId = folderId,
                conversationId = conversationId,
            )
        )
    }

    @Test
    fun `legacy path can be read by normalized form`() = runBlocking {
        seedLegacy("旧标题.md", "老记忆正文")
        // 用户/模型总是传带斜杠的规范形态；旧实现会返回 null（明明在列表里）
        val found = repository.getFile(assistant, "/旧标题.md")
        assertEquals("老记忆正文", found?.content)
    }

    @Test
    fun `editing a legacy path migrates instead of duplicating`() = runBlocking {
        seedLegacy("旧标题.md", "老内容")
        val result = repository.writeFile(assistant, "/旧标题.md", "新内容")
        assertTrue(result is MemoryWriteResult.Success)
        // 关键：不能变成两条（一条无斜杠、一条有斜杠）
        val all = dao.getFilesOfAssistant(assistant)
        assertEquals("归一化写入后应只有一条", 1, all.size)
        assertEquals("/旧标题.md", all.first().path)
        assertEquals("新内容", all.first().content)
    }

    @Test
    fun `changing scope of a legacy path reports true`() = runBlocking {
        seedLegacy("旧标题.md", "老内容")
        // 旧实现：normalizePath 单形态 → 0 行命中，但返回恒 true（假成功）
        assertTrue(repository.updateScope(assistant, "/旧标题.md", "folder-A", null))
        val file = repository.getFile(assistant, "/旧标题.md")
        assertEquals("folder-A", file?.folderId)
    }

    @Test
    fun `changing scope of a truly missing path reports false`() = runBlocking {
        // 旧实现恒 true —— 用户点了「归属」看不出失败
        assertTrue(!repository.updateScope(assistant, "/nope.md", "folder-A", null))
    }

    @Test
    fun `cross scope overwrite is rejected`() = runBlocking {
        // 助手级（全员可见）已有 /topics/x.md
        repository.writeFile(assistant, "/topics/x.md", "助手级内容", MemoryRepository.NEW_FILE_VERSION)
        // 项目包里想写同一路径 —— 旧实现会直接覆盖助手级那条（保密内容串成全员可见）
        val r = repository.writeFile(
            assistant, "/topics/x.md", "包内私密内容",
            ifVersion = null, folderId = "folder-A",
        )
        assertTrue("跨范围覆盖必须被拒绝: $r", r is MemoryWriteResult.Rejected)
        // 助手级内容必须原样保留
        assertEquals("助手级内容", repository.getFile(assistant, "/topics/x.md")?.content)
    }

    @Test
    fun `reading respects scope so other scopes are invisible`() = runBlocking {
        repository.writeFile(assistant, "/a.md", "助手级", MemoryRepository.NEW_FILE_VERSION)
        repository.writeFile(assistant, "/b.md", "包A私密", MemoryRepository.NEW_FILE_VERSION, folderId = "folder-A")
        repository.writeFile(assistant, "/c.md", "对话X私密", MemoryRepository.NEW_FILE_VERSION, conversationId = "conv-X")

        // 在「项目包 B / 对话 Y」的上下文里读：
        assertTrue("助手级在任何上下文都可见", repository.readVisibleFile(assistant, "/a.md", "folder-B", "conv-Y") != null)
        assertNull("别人的项目包不可见", repository.readVisibleFile(assistant, "/b.md", "folder-B", "conv-Y"))
        assertNull("别人的单对话不可见", repository.readVisibleFile(assistant, "/c.md", "folder-B", "conv-Y"))
        // 本包上下文可见自己的包记忆
        assertEquals("包A私密", repository.readVisibleFile(assistant, "/b.md", "folder-A", "conv-Z")?.content)
        // 本对话可见自己的对话记忆
        assertEquals("对话X私密", repository.readVisibleFile(assistant, "/c.md", "folder-B", "conv-X")?.content)
    }

    @Test
    fun `add memory auto suffixes on title conflict`() = runBlocking {
        val first = repository.addMemory(assistant, "同标题\n第一条")
        val second = repository.addMemory(assistant, "同标题\n第二条")
        assertTrue("两次新建必须都能落库", dao.getFilesOfAssistant(assistant).size == 2)
        assertTrue("第一条内容保留", repository.getFile(assistant, first.path!!)?.content == "同标题\n第一条")
        assertTrue("第二条内容保留", repository.getFile(assistant, second.path!!)?.content == "同标题\n第二条")
    }

    // ══════════════════════════════════════════════════════════════════════
    // v5.3.20 ★批量可见读取（性能回归护栏）
    //
    // 旧工具层对每个 path 单独调一次 readVisibleFile（内部全表扫描），
    // MAX_READ_PATHS=20 时 = 一次 memory_read 触发 20 次全表。改批量后只扫一遍。
    // 这条测试锁定「语义不变」：多形态 path 都能命中、跨范围仍然不可见。
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `batch visible read resolves all candidates in one pass`() = runBlocking {
        seedLegacy("旧标题.md", "老内容")                            // 无前导斜杠
        repository.writeFile(assistant, "/a.md", "助手级", MemoryRepository.NEW_FILE_VERSION)
        repository.writeFile(assistant, "/b.md", "包A", MemoryRepository.NEW_FILE_VERSION, folderId = "folder-A")
        repository.writeFile(assistant, "/c.md", "对话X", MemoryRepository.NEW_FILE_VERSION, conversationId = "conv-X")

        // 一次调用拿多个：规范形态、无斜杠形态、跨范围、不存在
        val got = repository.readVisibleFiles(
            memoryId = assistant,
            paths = listOf("/旧标题.md", "a.md", "/b.md", "/c.md", "/nope.md"),
            folderId = "folder-B",          // 在包 B 的上下文
            conversationId = "conv-Y",      // 对话 Y 的上下文
        )
        assertEquals("老 path 的规范形态应命中", "老内容", got["/旧标题.md"]?.content)
        assertEquals("无前导斜杠入参应命中", "助手级", got["a.md"]?.content)
        assertTrue("别的项目包必须不可见", "/b.md" !in got)
        assertTrue("别的单对话必须不可见", "/c.md" !in got)
        assertTrue("不存在的 path 不应出现", "/nope.md" !in got)

        // 本包/本对话上下文应命中
        val mine = repository.readVisibleFiles(
            memoryId = assistant,
            paths = listOf("/b.md", "/c.md"),
            folderId = "folder-A",
            conversationId = "conv-X",
        )
        assertEquals("包A", mine["/b.md"]?.content)
        assertEquals("对话X", mine["/c.md"]?.content)
    }

    @Test
    fun `batch visible read returns empty for empty input`() = runBlocking {
        assertTrue(repository.readVisibleFiles(assistant, emptyList(), null, null).isEmpty())
    }

    // ══════════════════════════════════════════════════════════════════════
    // v5.3.22 ★★补回原版 3.1.1 的两条边界（移植 3.0.0 时丢失，用户实测"越用越稀"）
    //
    // 原版 MemoryRepository.kt:186-200 与 :150 各有一段判据，我们的重写版
    // 简化成一行 substring / 无条件拦大小 —— 这是「不看原版」的直接代价。
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `replace removing a whole line leaves no blank line`() = runBlocking {
        val initial = "- 第一行\n- 要删的行\n- 第三行"
        repository.writeFile(assistant, "/a.md", initial, MemoryRepository.NEW_FILE_VERSION)
        val version = repository.getFile(assistant, "/a.md")!!.version

        // 删掉完整的一行（含它前面的换行处理）
        val r = repository.replaceInFile(assistant, "/a.md", "- 要删的行\n", "", version)
        assertTrue("替换应成功: $r", r is MemoryWriteResult.Success)
        val out = repository.getFile(assistant, "/a.md")!!.content
        assertEquals("- 第一行\n- 第三行", out)
        assertTrue("不得出现连续空行: <$out>", !out.contains("\n\n"))
    }

    @Test
    fun `oversized legacy memory can still be shrunk`() = runBlocking {
        // 模拟迁移来的超限旧记忆（> 48KB）
        val huge = "x".repeat(MemoryFile.MAX_BYTES + 1024)
        dao.upsertFile(
            me.rerere.rikkahub.data.db.entity.MemoryFileEntity(
                assistantId = assistant,
                path = "/legacy.md",
                content = huge,
                updatedAt = 1L,
                source = "active",
            )
        )
        // 原版判据：只拦"变大"的超限写入 → 变小必须放行
        val r = repository.writeFile(assistant, "/legacy.md", "只有一小段")
        assertTrue("超限旧记忆必须允许改小，实际=$r", r is MemoryWriteResult.Success)
        assertEquals("只有一小段", repository.getFile(assistant, "/legacy.md")?.content)
    }

    @Test
    fun `oversized new write is still rejected`() = runBlocking {
        val huge = "x".repeat(MemoryFile.MAX_BYTES + 1024)
        val r = repository.writeFile(assistant, "/new-huge.md", huge, MemoryRepository.NEW_FILE_VERSION)
        assertTrue("超限的新写入必须拒绝: $r", r is MemoryWriteResult.Rejected)
    }

    // ══════════════════════════════════════════════════════════════════════
    // v5.3.24 ★★★三层归属 × 挂载层级 的可见性契约
    //
    // 用户视角的规则：设成「仅助手级」就只该看到助手级；「项目包内」看到
    // 助手级 + 本包 + 包内对话（含同包兄弟）；「仅当前对话」看到助手级 + 本对话
    // （**不含**同包兄弟）。改动任何一处判据都会让这三条先红。
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun createIn(
        path: String,
        folderId: String? = null,
        conversationId: String? = null,
        source: String = "active",
    ) {
        repository.writeFile(
            assistantId = assistant,
            path = path,
            content = "- [stated] x",
            ifVersion = MemoryRepository.NEW_FILE_VERSION,
            source = source,
            folderId = folderId,
            conversationId = conversationId,
        )
    }

    private fun visiblePaths(mount: Int): Set<String> = runBlocking {
        repository.memoriesForInjection(
            memoryId = assistant,
            folderId = "folderA",
            conversationId = "convA",
            siblingConversationIds = setOf("convA", "convB"),
            manualMount = mount,
            dreamMount = mount,
        ).map { it.path }.toSet()
    }

    private fun seedLayers() = runBlocking {
        createIn("/assistant.md")
        createIn("/folder.md", folderId = "folderA")
        createIn("/own.md", conversationId = "convA")
        createIn("/sibling.md", conversationId = "convB")
    }

    @Test
    fun `mount level decides which layers reach the model`() {
        seedLayers()

        assertEquals(
            "仅助手级挂载只能看见助手级记忆",
            setOf("/assistant.md"),
            visiblePaths(MemoryRepository.MOUNT_ASSISTANT_ONLY),
        )
        assertEquals(
            "项目包内挂载应看见全部四层（含同包兄弟对话）",
            setOf("/assistant.md", "/folder.md", "/own.md", "/sibling.md"),
            visiblePaths(MemoryRepository.MOUNT_FOLDER),
        )
        assertEquals(
            "仅当前对话挂载应排除同包兄弟对话的记忆",
            setOf("/assistant.md", "/own.md"),
            visiblePaths(MemoryRepository.MOUNT_CURRENT_CONVERSATION),
        )
    }

    @Test
    fun `manual and dream mounts are applied separately`() = runBlocking {
        seedLayers()
        createIn("/dream-folder.md", folderId = "folderA", source = "dream")

        val visible = repository.memoriesForInjection(
            memoryId = assistant,
            folderId = "folderA",
            conversationId = "convA",
            siblingConversationIds = setOf("convA", "convB"),
            manualMount = MemoryRepository.MOUNT_ASSISTANT_ONLY,
            dreamMount = MemoryRepository.MOUNT_FOLDER,
        ).map { it.path }.toSet()

        assertTrue("主动记忆按 manualMount 收缩：包层条目不可见", "/folder.md" !in visible)
        assertTrue("做梦记忆按 dreamMount 放开：包层做梦条目可见", "/dream-folder.md" in visible)
        assertTrue("助手级主动记忆始终可见", "/assistant.md" in visible)
    }

    @Test
    fun `listing and reading use the same visibility as injection`() {
        seedLayers()

        // 模型从上下文能看见的集合
        val injected = runBlocking {
            repository.memoriesForInjection(
                memoryId = assistant,
                folderId = "folderA",
                conversationId = "convA",
                siblingConversationIds = setOf("convA", "convB"),
                manualMount = MemoryRepository.MOUNT_ASSISTANT_ONLY,
            ).map { it.path }.toSet()
        }
        // 工具层 list 能列出的集合（必须与上面完全相同，否则「设置成什么样不是什么样」）
        val listed = runBlocking {
            repository.listVisibleFiles(
                memoryId = assistant,
                folderId = "folderA",
                conversationId = "convA",
                manualMount = MemoryRepository.MOUNT_ASSISTANT_ONLY,
            ).map { it.path }.toSet()
        }
        // 工具层 read 一条包层记忆（应读不到）
        val readFolderLevel = runBlocking {
            repository.readVisibleFiles(
                memoryId = assistant,
                paths = listOf("/folder.md"),
                folderId = "folderA",
                conversationId = "convA",
                manualMount = MemoryRepository.MOUNT_ASSISTANT_ONLY,
            )
        }

        assertEquals("list 必须与注入同一口径", injected, listed)
        assertTrue("包层记忆在仅助手级挂载下不可读", readFolderLevel.isEmpty())
    }
}
