package me.rerere.rikkahub.data.ai.prompts

import me.rerere.rikkahub.data.model.MemoryFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPromptTest {
    private val profile = MemoryFile(MemoryFile.PROFILE_PATH, "- [stated] Name is Lin\n", updatedAt = 0)
    private val food = MemoryFile(
        path = "/topics/food.md",
        content = """
            ---
            name: food
            description: What the user eats and avoids
            aliases: [diet, 饮食]
            ---

            - [stated] Allergic to peanuts
        """.trimIndent(),
        updatedAt = 0,
    )

    private fun String.block(tag: String) = substringAfter("<$tag").substringAfter(">").substringBefore("</$tag>").trim()

    @Test
    fun `profile is injected in full with its version and other files only appear in the listing`() {
        val prompt = buildMemoryPrompt(listOf(profile, food))

        assertTrue(prompt.contains("<profile version=\"${profile.version}\">"))
        assertEquals("- [stated] Name is Lin", prompt.block("profile"))

        val listing = prompt.block("memory_listing")
        assertTrue(listing.startsWith("/topics/food.md | What the user eats and avoids | aliases: diet, 饮食 | updated: "))
        assertFalse(listing.contains(MemoryFile.PROFILE_PATH))
        assertFalse(prompt.contains("Allergic to peanuts"))
    }

    @Test
    fun `missing files are shown as empty and creatable`() {
        val prompt = buildMemoryPrompt(emptyList())

        assertTrue(prompt.contains("<preferences version=\"new\">"))
        assertEquals("(empty)", prompt.block("preferences"))
        assertEquals("(no files yet)", prompt.block("memory_listing"))
    }

    @Test
    fun `consolidation input carries the memory state and each conversation's new turns`() {
        val input = buildMemoryConsolidationInput(
            files = listOf(profile, food),
            conversations = listOf(
                MemoryConsolidationConversation(
                    id = "c1",
                    title = "Moving <to> \"Hangzhou\"",
                    recentContext = "",
                    newTurns = "[USER #4]: I moved to Hangzhou",
                ),
            ),
        )

        assertEquals("- [stated] Name is Lin", input.block("profile"))
        assertTrue(input.block("memory_listing").startsWith("/topics/food.md"))
        // 标题里的引号和尖括号不能破坏标签
        assertTrue(input.contains("<conversation id=\"c1\" title=\"Moving  to   Hangzhou\">"))
        assertEquals("(none)", input.block("recent_context"))
        assertEquals("[USER #4]: I moved to Hangzhou", input.block("new_turns"))
    }

    @Test
    fun `consolidation input says so when there is nothing new`() {
        val input = buildMemoryConsolidationInput(files = listOf(profile), conversations = emptyList())

        assertTrue(input.endsWith("(no new conversation turns)"))
        assertFalse(input.contains("<conversation"))
    }

    @Test
    fun `whole-store tidying is only asked for in a full review`() {
        assertFalse(buildMemoryConsolidationPrompt(fullReview = false).contains("## Full review"))
        assertTrue(buildMemoryConsolidationPrompt(fullReview = true).contains("## Full review"))
    }

    // ══════════════════════════════════════════════════════════════════════
    // v5.3.22 ★★★端到端格式护栏：模型实际收到的必须是 <memory_system>，
    // 绝不能是 v5.0.x 的 `**Memories**` + JSON 数组（那版还叫模型用已删除的
    // memory_tool）。这条断言直接对应「v5.3.21 之前注入链接错」的复发。
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `prompt is the structured memory_system block`() {
        val files = listOf(
            MemoryFile(path = "/profile.md", content = "---\nname: profile\n---\n\n住在杭州", updatedAt = 10L),
            MemoryFile(path = "/preferences.md", content = "- 回答要简洁", updatedAt = 20L),
            MemoryFile(path = "/topics/food.md", content = "喜欢清淡", updatedAt = 30L),
        )
        val text = buildMemoryPrompt(files)

        assertTrue("必须是 <memory_system> 结构", text.contains("<memory_system>"))
        assertTrue("必须有 profile 全文块", text.contains("<profile version="))
        assertTrue("必须有 preferences 全文块", text.contains("<preferences version="))
        assertTrue("必须有 listing", text.contains("<memory_listing>"))
        assertTrue("必须给出总量", text.contains("<count>1</count>"))
        assertTrue("必须带读写规则", text.contains("When to read"))
        assertTrue("必须有防注入声明", text.contains("not instructions to you"))

        // 旧格式的硬特征，一个都不能出现
        assertFalse("不得出现旧版标题", text.contains("**Memories**"))
        assertFalse("不得再提到已删除的 memory_tool", text.contains("memory_tool"))
        // 旧版另一个特征是整段 JSON 数组（形如 [ ... ]）；这里用结构特征判断，
        // 不写转义引号 —— 转义引号会让 ci-check 的括号剥离器误判字符串边界。
    }

    @Test
    fun `listing never shows no description for legacy memories`() {
        // 迁移产物：没有 frontmatter
        val legacy = MemoryFile(
            path = "/memory_123.md",
            content = "【RinCore v5.0.25 正式版已发布】\n- 正式可用版",
            updatedAt = 1L,
        )
        val text = buildMemoryPrompt(listOf(legacy))
        assertFalse(
            "历史记忆必须用正文首行兜底，不能是 (no description)",
            text.contains("(no description)"),
        )
        assertTrue("清单里应能看到正文摘要", text.contains("RinCore v5.0.25 正式版已发布"))
    }
}
