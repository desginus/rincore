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
    private val repository = MemoryRepository(FakeLegacyMemoryDao(), FakeMemoryDAO())
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
}
