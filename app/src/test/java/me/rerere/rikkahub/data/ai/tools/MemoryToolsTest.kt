package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.FakeMemoryDAO
import me.rerere.rikkahub.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryToolsTest {
    private val repository = MemoryRepository(FakeMemoryDAO())
    private val tools = buildMemoryTools(repository, "assistant").associateBy { it.name }

    private suspend fun call(name: String, args: JsonObjectBuilder.() -> Unit): JsonObject {
        val output = tools.getValue(name).execute(buildJsonObject(args))
        return Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
    }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.content

    @Test
    fun `a written file can be read back and edited with the returned version`() = runBlocking {
        val written = call(MemoryToolNames.WRITE) {
            put("path", "/topics/food.md")
            put("content", "- [stated] Likes noodles\n")
            put("if_version", "new")
        }
        val read = call(MemoryToolNames.READ) {
            put("path", buildJsonArray { add("/topics/food.md"); add("/topics/none.md") })
        }
        val file = read.getValue("files").jsonArray.single().jsonObject

        assertEquals(written.string("version"), file.string("version"))
        assertEquals("- [stated] Likes noodles\n", file.string("content"))
        assertEquals(listOf("/topics/none.md"), read.getValue("not_found").jsonArray.map { it.jsonPrimitive.content })

        val appended = call(MemoryToolNames.APPEND) {
            put("path", "/topics/food.md")
            put("content", "- [stated] Allergic to peanuts")
            put("if_version", file.string("version"))
        }
        assertNull(appended["error"])
        assertEquals(
            "- [stated] Likes noodles\n- [stated] Allergic to peanuts\n",
            repository.getFile("assistant", "/topics/food.md")?.content,
        )
    }

    @Test
    fun `memory_read accepts a single path string`() = runBlocking {
        repository.writeFile("assistant", "/profile.md", "- [stated] Name is Lin\n")

        val read = call(MemoryToolNames.READ) { put("path", "/profile.md") }

        assertEquals("/profile.md", read.getValue("files").jsonArray.single().jsonObject.string("path"))
    }

    @Test
    fun `a version conflict returns the latest content so the edit can be retried`() = runBlocking {
        repository.writeFile("assistant", "/topics/food.md", "- [stated] Likes noodles\n")
        val current = repository.getFile("assistant", "/topics/food.md")!!

        val conflict = call(MemoryToolNames.STR_REPLACE) {
            put("path", "/topics/food.md")
            put("old_str", "noodles")
            put("new_str", "rice")
            put("if_version", "stale")
        }

        assertNotNull(conflict["error"])
        assertEquals(current.version, conflict.string("current_version"))
        assertEquals(current.content, conflict.string("current_content"))

        val retried = call(MemoryToolNames.STR_REPLACE) {
            put("path", "/topics/food.md")
            put("old_str", "noodles")
            put("new_str", "rice")
            put("if_version", conflict.string("current_version"))
        }
        assertNull(retried["error"])
        assertEquals("- [stated] Likes rice\n", repository.getFile("assistant", "/topics/food.md")?.content)
    }

    @Test
    fun `editing a missing file points to creating it`() = runBlocking {
        val result = call(MemoryToolNames.APPEND) {
            put("path", "/topics/none.md")
            put("content", "- line")
            put("if_version", "abc")
        }

        assertTrue(result.string("error")!!.contains("does not exist"))
        assertNull(result["current_content"])
    }

    @Test
    fun `card and ID numbers are refused and nothing is written`() = runBlocking {
        val card = call(MemoryToolNames.WRITE) {
            put("path", "/topics/finance.md")
            put("content", "- [stated] Card number is 4111 1111 1111 1111\n")
            put("if_version", "new")
        }
        val id = call(MemoryToolNames.WRITE) {
            put("path", "/profile.md")
            put("content", "- [stated] 身份证号11010519491231002X\n")
            put("if_version", "new")
        }

        assertTrue(card.string("error")!!.contains("bank card number"))
        assertTrue(id.string("error")!!.contains("ID number"))
        assertTrue(repository.getFiles("assistant").isEmpty())
    }

    @Test
    fun `ordinary numbers are not mistaken for card or ID numbers`() {
        assertNull(findForbiddenMemoryContent("Phone is +86 138 0013 8000, order 2026100812345678"))
        assertNull(findForbiddenMemoryContent("Born 1990-05-17, moved in 2021"))
        assertNotNull(findForbiddenMemoryContent("SSN 123-45-6789"))
    }

    @Test
    fun `memory_delete removes the file and memory_list reflects it`() = runBlocking {
        repository.writeFile("assistant", "/people/mom.md", "content")
        repository.writeFile("assistant", "/topics/food.md", "content")
        val version = repository.getFile("assistant", "/people/mom.md")!!.version

        val people = call(MemoryToolNames.LIST) { put("path_prefix", "/people/") }
        assertEquals(listOf("/people/mom.md"), people.getValue("files").jsonArray.map { it.jsonObject.string("path") })

        val deleted = call(MemoryToolNames.DELETE) {
            put("path", "/people/mom.md")
            put("if_version", version)
        }
        assertEquals("true", deleted.string("deleted"))
        assertFalse(call(MemoryToolNames.LIST) {}.toString().contains("/people/mom.md"))
    }

    // ══════════════════════════════════════════════════════════════════════
    // v5.3.24 ★★「设置成什么样，它就是什么样」的写入侧护栏
    //
    // 挂载层级决定模型能看见哪些层；若写入落进看不见的层，这条记忆下一轮
    // 不会被注入 = 写了个黑洞。以下四条锁定收缩规则。
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `write scope shrinks to the assistant level when mount is assistant only`() {
        val (folder, conversation) = memoryWriteScopeFor(
            mount = MemoryRepository.MOUNT_ASSISTANT_ONLY,
            folderId = "folderA",
            conversationId = "convA",
        )
        assertNull("仅助手级挂载下不应写入项目包", folder)
        assertNull("仅助手级挂载下不应写入对话层", conversation)
    }

    @Test
    fun `write scope keeps only the conversation when mount is this conversation`() {
        val (folder, conversation) = memoryWriteScopeFor(
            mount = MemoryRepository.MOUNT_CURRENT_CONVERSATION,
            folderId = "folderA",
            conversationId = "convA",
        )
        assertNull("仅当前对话挂载下项目包层不可见 → 不应写入", folder)
        assertEquals("convA", conversation)
    }

    @Test
    fun `write scope is unchanged at the folder mount`() {
        val (folder, conversation) = memoryWriteScopeFor(
            mount = MemoryRepository.MOUNT_FOLDER,
            folderId = "folderA",
            conversationId = null,
        )
        assertEquals("folderA", folder)
        assertNull(conversation)
    }

    @Test
    fun `blank scope values collapse to the assistant level`() {
        val (folder, conversation) = memoryWriteScopeFor(
            mount = MemoryRepository.MOUNT_FOLDER,
            folderId = "   ",
            conversationId = "",
        )
        assertNull("空白 folderId 必须归一为助手级", folder)
        assertNull("空白 conversationId 必须归一为助手级", conversation)
    }
}
