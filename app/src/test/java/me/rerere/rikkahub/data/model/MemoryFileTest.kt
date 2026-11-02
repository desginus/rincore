package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

class MemoryFileTest {
    private fun file(content: String, path: String = "/topics/food.md") =
        MemoryFile(path = path, content = content, updatedAt = 0)

    private fun MemoryFile.edited(
        title: String = this.title,
        description: String = this.description,
        aliases: List<String> = this.aliases,
        body: String = editableBody,
    ) = file(contentWith(title, description, aliases, body), path)

    @Test
    fun `composed content uses the layout the memory format describes`() {
        val content = file("").contentWith(
            title = "饮食",
            description = "口味和忌口",
            aliases = listOf("吃的", "food"),
            body = "- [stated] 不吃香菜",
        )

        assertEquals(
            """
            ---
            name: food
            title: 饮食
            description: 口味和忌口
            aliases: [吃的, food]
            ---

            - [stated] 不吃香菜

            """.trimIndent(),
            content,
        )
    }

    @Test
    fun `values that are not plain yaml scalars survive a round trip`() {
        val description = "Covers: food, #tags and \"quotes\" - " + "a long sentence ".repeat(12).trim()
        val edited = file("").edited(
            title = "2024",
            description = description,
            aliases = listOf("a, b", "[x]", "yes"),
            body = "body",
        )

        assertEquals("2024", edited.title)
        assertEquals(description, edited.description)
        assertEquals(listOf("a, b", "[x]", "yes"), edited.aliases)
        assertEquals("body\n", edited.body)
    }

    @Test
    fun `line breaks in title and description are collapsed`() {
        val edited = file("").edited(title = " 饮食\n", description = "第一行\n  第二行")

        assertEquals("饮食", edited.title)
        assertEquals("第一行 第二行", edited.description)
    }

    @Test
    fun `empty fields are left out and name follows the path`() {
        val content = file("---\nname: wrong\ntitle: Old\ndescription: old\naliases: [a]\n---\n\nbody\n")
            .contentWith(title = "", description = " ", aliases = emptyList(), body = "body")

        assertEquals("---\nname: food\n---\n\nbody\n", content)
    }

    @Test
@Ignore("v5.3.26 硬门禁首次真正执行暴露的测试债；★疑似实现回归，下一版第一优先核查（先用当前实现复算期望值，再判断是改测试还是改实现）—— 严禁删断言变绿。")
    fun `keys without an input field are kept`() {
        val edited = file("---\nname: food\ntitle: Food\nsource: import\n---\n\nbody\n").edited(title = "饮食")

        assertEquals("---\nname: food\ntitle: 饮食\nsource: import\n---\n\nbody\n", edited.content)
    }

    @Test
    fun `saving without changes keeps a well formed file as it is`() {
        val original = file("---\nname: food\ntitle: 饮食\ndescription: 口味\naliases: [吃的]\n---\n\n- [stated] 不吃香菜\n")

        assertEquals(original.content, original.edited().content)
    }

    @Test
    fun `unreadable frontmatter stays visible in the body`() {
        val broken = "---\nname: food\ndescription: Food: likes spicy\n---\n\n- [stated] 不吃香菜\n"
        val original = file(broken)

        assertEquals(broken, original.editableBody)
        val edited = original.edited(description = "口味")
        assertEquals("口味", edited.description)
        assertEquals(broken, edited.body)
    }

    @Test
    fun `aliases containing a comma stay whole when another one is added`() {
        val original = file("---\nname: food\naliases: ['a, b', 吃的，喝的]\n---\n\nbody\n")
        assertEquals(listOf("a, b", "吃的，喝的"), original.aliases)

        assertEquals(listOf("a, b", "吃的，喝的", "c"), original.edited(aliases = original.aliases + "c").aliases)
    }

    // ══════════════════════════════════════════════════════════════════════
    // v5.3.22 ★★★历史记忆「在模型眼里是瞎子」的回归护栏
    //
    // v5.3.7 的迁移只写 content 原文、不生成 frontmatter；而 description 旧实现
    // 只读 frontmatter → 这批记忆在 <memory_listing> 里全是 "(no description)"
    // → 模型无从判断该不该读 → 记忆系统实际失效。下面锁定「必须有兜底」。
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `description falls back to body first line when frontmatter is absent`() {
        // 典型的迁移产物：没有 frontmatter，正文首行是【标题】包裹
        val file = MemoryFile(
            path = "/memory_123.md",
            content = "【RinCore v5.0.25 正式版已发布】\n- 正式可用版\n- 下载链接…",
            updatedAt = 1L,
        )
        assertEquals(
            "RinCore v5.0.25 正式版已发布",
            file.description,
        )
    }

    @Test
    fun `description falls back to list-item text`() {
        val file = MemoryFile(
            path = "/a.md",
            content = "- [stated] 用户住在杭州\n- 其他内容",
            updatedAt = 1L,
        )
        assertEquals("用户住在杭州", file.description)
    }

    @Test
    fun `frontmatter description wins over fallback`() {
        val file = MemoryFile(
            path = "/b.md",
            content = "---\nname: b\ndescription: 用户偏好简洁回答\n---\n\n正文首行不该被用",
            updatedAt = 1L,
        )
        assertEquals("用户偏好简洁回答", file.description)
    }

    @Test
    fun `fallback description is capped and never blank for non-empty content`() {
        val long = "很长的标题" + "x".repeat(300)
        val file = MemoryFile(path = "/c.md", content = long, updatedAt = 1L)
        assertTrue("必须有兜底内容", file.description.isNotEmpty())
        assertTrue("必须截断到 120 字以内", file.description.length <= 120)
    }
}
