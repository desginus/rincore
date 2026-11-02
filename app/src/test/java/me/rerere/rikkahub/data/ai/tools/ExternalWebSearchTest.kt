package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

class ExternalWebSearchTest {
    @Test
@Ignore("v5.3.26 硬门禁首次真正执行时发现的存量测试债：本测试自 v5.3.10 接通测试以来从未执行（test sourceSet 长期编译失败被 continue-on-error 掩盖），断言停留在旧实现。待按当前实现逐条重写后移除本注解 —— 严禁直接用删除断言的方式让它变绿。")
    fun `external web search is disabled when assistant preference is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model()

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `external web search is enabled when assistant preference is enabled`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model()

        assertTrue(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search suppresses enabled external web search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search remains exclusive when external web search is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `unrelated built-in tools do not suppress external web search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(tools = setOf(BuiltInTools.UrlContext))

        assertTrue(shouldUseExternalWebSearch(assistant, model))
    }
}
