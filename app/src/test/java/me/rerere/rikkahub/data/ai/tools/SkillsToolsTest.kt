/* 【域 C·工具系统】 — 单元测试 | 2.5.5 适配 */
package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillsToolsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `use_skill accepts escaped skill name and description is capped`() = runBlocking {
        val skillDir = tempFolder.newFolder("escape")
        skillDir.resolve("SKILL.md").writeText("---\nname: a&b\ndescription: test\n---\nEscaped body")
        val skill = SkillMetadata(
            name = "a&b",
            description = "x".repeat(2000),
            skillDir = skillDir,
        )
        val tools = createSkillTools(allSkills = listOf(skill))
        val useSkill = tools.first { it.name == "use_skill" }
        val skillTool = tools.first { it.name.startsWith("skill_") }

        // 模型照抄转义后的名称也能加载 (2.5.5 移植行为)
        val result = useSkill.execute(buildJsonObject { put("name", "a&amp;b") })
        assertEquals("Escaped body", (result.single() as UIMessagePart.Text).text)

        // description 渲染给模型前截断至 1024 (规范一致)
        assertTrue(skillTool.description.length <= 1024)
    }
}
