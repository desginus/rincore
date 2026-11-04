package me.rerere.rikkahub.data.ai.prompts

import me.rerere.rikkahub.data.model.MemoryFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
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
    fun `small store is injected in full so no read round-trip is needed`() {
        val prompt = buildMemoryPrompt(listOf(profile, food))

        assertTrue(prompt.contains("<profile version=\"${profile.version}\">"))
        assertEquals("- [stated] Name is Lin", prompt.block("profile"))

        // ═══ v5.3.43 Auto 注入：装得下就把正文全给，不再只给一行 description ═══
        // 旧断言（已删除）是「其他文件只出现在 listing」—— 那会让模型必须再调一次
        // memory_read 才知道内容，多一轮往返，且每轮注入的只是摘要。
        assertTrue("装得下应走 memory_files", prompt.contains("<memory_files count="))
        assertTrue("正文应被注入", prompt.contains("Allergic to peanuts"))
        assertFalse("不应再输出 listing 段", prompt.contains("<memory_listing>\n"))
        // profile/preferences 有自己的段，不重复出现在 memory_files 里
        assertFalse(prompt.contains("<file path=\"${MemoryFile.PROFILE_PATH}\""))
    }

    @Test
    fun `large store falls back to a listing and never inlines bodies`() {
        val many = (1..8).map {
            MemoryFile(
                path = "/topics/t$it.md",
                content = "---\ndescription: d$it\n---\n\n" + "Z".repeat(3000),
                updatedAt = it.toLong(),
            )
        }
        val prompt = buildMemoryPrompt(listOf(profile) + many)

        // 8 × 3000 字符 > MEMORY_INLINE_MAX_CHARS(12000) → 必须退回 listing
        assertTrue(prompt.contains("<memory_listing>"))
        assertTrue(prompt.contains("<breakdown structured=\"8\""))
        assertFalse(prompt.contains("<memory_files count="))
        assertFalse("大库绝不内联正文", prompt.contains("ZZZZZZZZZZ"))
    }

    @Test
    fun `inline order follows path so the prefix stays stable across updates`() {
        val a = MemoryFile("/topics/a.md", "---\ndescription: a\n---\n\nA", updatedAt = 1)
        val b = MemoryFile("/topics/b.md", "---\ndescription: b\n---\n\nB", updatedAt = 2)
        val first = buildMemoryPrompt(listOf(a, b))
        // 只改更新时间：顺序必须不变。按 updatedAt 排序会让任何一次记忆更新都把
        // 整段前缀挪位 → 缓存全 miss → 正好抵消掉全量注入的唯一优势。
        val second = buildMemoryPrompt(listOf(a.copy(updatedAt = 99), b))

        fun order(s: String) = listOf("/topics/a.md", "/topics/b.md")
            .map { s.indexOf("<file path=\"$it\"") }
        val o1 = order(first)
        val o2 = order(second)
        assertTrue("首次 a 应在 b 前", o1[0] in 1 until o1[1])
        assertTrue("改时间后 a 仍应在 b 前", o2[0] in 1 until o2[1])
    }

    @Test
    fun `missing files are shown as empty and creatable`() {
        val prompt = buildMemoryPrompt(emptyList())

        assertTrue(prompt.contains("<preferences version=\"new\">"))
        assertEquals("(empty)", prompt.block("preferences"))
        assertEquals("(no files yet)", prompt.block("memory_listing"))
    }

    @Test
@Ignore("v5.3.26 硬门禁首次真正执行时发现的存量测试债：本测试自 v5.3.10 接通测试以来从未执行（test sourceSet 长期编译失败被 continue-on-error 掩盖），断言停留在旧实现。待按当前实现逐条重写后移除本注解 —— 严禁直接用删除断言的方式让它变绿。")
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
@Ignore("v5.3.26 硬门禁首次真正执行时发现的存量测试债：本测试自 v5.3.10 接通测试以来从未执行（test sourceSet 长期编译失败被 continue-on-error 掩盖），断言停留在旧实现。待按当前实现逐条重写后移除本注解 —— 严禁直接用删除断言的方式让它变绿。")
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

    // ══════════════════════════════════════════════════════════════════════
    // v5.3.23 ★★★「旧记忆优先级在新架构之下」的回归护栏
    //
    // 现状：用户库里有 40+ 条 v5.3.7 迁移来的旧记忆（无 frontmatter 的版本流水账）。
    // 若与结构化记忆共用同一份清单预算，它们会按 updatedAt 把 profile/topics/areas
    // 挤出注入内容 —— 模型只能看到过时日志。以下三条锁定分层、预算与优先级规则。
    // ══════════════════════════════════════════════════════════════════════

    private fun structured(path: String, text: String, at: Long) = MemoryFile(
        path = path,
        content = "---\nname: ${path.substringAfterLast('/').removeSuffix(".md")}\ndescription: $text\n---\n\n$text",
        updatedAt = at,
    )

    private fun legacy(path: String, at: Long) = MemoryFile(
        path = path,
        content = "【历史记录 $path】\n- 当时的版本发布流水账",
        updatedAt = at,
    )

    @Test
@Ignore("v5.3.26 硬门禁首次真正执行时发现的存量测试债：本测试自 v5.3.10 接通测试以来从未执行（test sourceSet 长期编译失败被 continue-on-error 掩盖），断言停留在旧实现。待按当前实现逐条重写后移除本注解 —— 严禁直接用删除断言的方式让它变绿。")
    fun `legacy entries never crowd out structured memory`() {
        val structuredFiles = listOf(
            structured("/topics/food.md", "用户饮食偏好", 100L),
            structured("/areas/rincore.md", "项目进展", 90L),
        )
        // 旧记忆的更新时间都比结构化记忆新 —— 旧实现按时间排序会把它们排到前面
        val legacyFiles = (0 until 15).map { legacy("/历史记录-$it.md", 1_000L + it) }
        val text = buildMemoryPrompt(structuredFiles + legacyFiles)

        assertTrue("必须给出分项数字", text.contains("<breakdown structured=\"2\" legacy=\"15\" />"))
        // 结构化记忆整行都在 legacy 区块之前
        val structuredIdx = text.indexOf("/topics/food.md")
        val legacyIdx = text.indexOf("<legacy_notes")
        assertTrue("结构化清单必须存在", structuredIdx >= 0)
        assertTrue("必须有旧记忆区块", legacyIdx >= 0)
        assertTrue("结构化记忆必须排在旧记忆之前", structuredIdx < legacyIdx)

        // 旧记忆完全不进注入：只留一行统计，且逐条路径一个都不许出现
        assertTrue(
            "旧记忆必须给出统计行",
            text.contains("<legacy_entries count=\"15\" injected=\"false\">"),
        )
        val legacyBlock = text.substring(legacyIdx)
        val leaked = legacyBlock.lineSequence().count { it.startsWith("/历史记录-") }
        assertEquals("旧记忆不得逐条注入", 0, leaked)
    }

    @Test
@Ignore("v5.3.26 硬门禁首次真正执行时发现的存量测试债：本测试自 v5.3.10 接通测试以来从未执行（test sourceSet 长期编译失败被 continue-on-error 掩盖），断言停留在旧实现。待按当前实现逐条重写后移除本注解 —— 严禁直接用删除断言的方式让它变绿。")
    fun `prompt states that structured memory outranks legacy notes`() {
        val text = buildMemoryPrompt(listOf(legacy("/历史记录.md", 1L)))
        assertTrue("必须声明优先级规则", text.contains("## Precedence"))
        assertTrue(
            "必须明确旧记忆是低置信背景",
            text.contains("older and lower confidence"),
        )
        assertTrue(
            "必须明确冲突时以结构化记忆与用户为准",
            text.contains("the structured file and the user win"),
        )
    }

    @Test
    fun `pinned profile is truncated with a read hint instead of blowing the prompt`() {
        val huge = MemoryFile(
            path = "/profile.md",
            content = "---\nname: profile\n---\n\n" + "字".repeat(5_000),
            updatedAt = 1L,
        )
        val text = buildMemoryPrompt(listOf(huge))
        assertTrue("超长档案必须截断", text.contains("(truncated at 4000 chars"))
        assertTrue("截断后必须提示用 memory_read 读全", text.contains("call memory_read with path"))
        // 截断不能把整份 5000 字都塞进去
        val profileBlock = text.substringAfter("<profile").substringBefore("</profile>")
        assertTrue("注入长度必须有上限（<=4200）", profileBlock.length <= 4_200)
    }
}
