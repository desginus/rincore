package me.rerere.rikkahub.data.repository

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★v5.3.69：流式增量落库的纯函数护栏。
 *
 * 旧实现每秒 `DELETE 全部节点 + INSERT 全部节点 + 全量 FTS 重建`；
 * 新实现只写变化的节点。这里锁死「什么算变化」：
 * 内容 / 节点序号 / 分支选中项 / 新增 / 删除 / 顺序变化 都必须被识别，
 * 任何无法判断的情况（缓存缺失）必须退回全量重写。
 */
class ConversationNodeWritePlanTest {

    private fun node(text: String, selectIndex: Int = 0): MessageNode = MessageNode(
        messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))
        ),
        selectIndex = selectIndex,
    )

    @Test
    fun `unchanged nodes produce an empty incremental plan`() {
        val a = node("a")
        val b = node("b")
        val plan = planNodeWrite(nodeFingerprints(listOf(a, b)), listOf(a, b))

        assertFalse(plan.fullRewrite)
        assertTrue(plan.changed.isEmpty())
        assertTrue(plan.removed.isEmpty())
    }

    @Test
    fun `only the changed node is rewritten`() {
        val a = node("a")
        val b = node("b")
        val bChanged = b.copy(
            messages = listOf(
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("b2")))
            )
        )
        val plan = planNodeWrite(nodeFingerprints(listOf(a, b)), listOf(a, bChanged))

        assertFalse(plan.fullRewrite)
        assertEquals(1, plan.changed.size)
        assertEquals(b.id, plan.changed.single().value.id)
        assertEquals(1, plan.changed.single().index)
        assertTrue(plan.removed.isEmpty())
    }

    @Test
    fun `select index change is treated as a change`() {
        val a = node("a")
        val aSelected = a.copy(selectIndex = 0)
        val cached = nodeFingerprints(listOf(a))
        val plan = planNodeWrite(cached, listOf(aSelected))

        // 同一个对象内容相同 → 不应重写
        assertTrue(plan.changed.isEmpty())

        val aOther = a.copy(selectIndex = 1)
        val plan2 = planNodeWrite(cached, listOf(aOther))
        assertEquals(1, plan2.changed.size)
    }

    @Test
    fun `removed node is reported`() {
        val a = node("a")
        val b = node("b")
        val plan = planNodeWrite(nodeFingerprints(listOf(a, b)), listOf(a))

        assertFalse(plan.fullRewrite)
        assertTrue(plan.changed.isEmpty())
        assertEquals(setOf(b.id.toString()), plan.removed)
    }

    @Test
    fun `added node is reported as changed`() {
        val a = node("a")
        val b = node("b")
        val plan = planNodeWrite(nodeFingerprints(listOf(a)), listOf(a, b))

        assertFalse(plan.fullRewrite)
        assertEquals(1, plan.changed.size)
        assertEquals(b.id, plan.changed.single().value.id)
        assertEquals(1, plan.changed.single().index)
    }

    @Test
    fun `reordered nodes are rewritten because their index changed`() {
        val a = node("a")
        val b = node("b")
        val plan = planNodeWrite(nodeFingerprints(listOf(a, b)), listOf(b, a))

        assertFalse(plan.fullRewrite)
        assertEquals(2, plan.changed.size)
        assertTrue(plan.removed.isEmpty())
    }

    @Test
    fun `missing cache forces a full rewrite`() {
        val plan = planNodeWrite(null, listOf(node("a")))
        assertTrue(plan.fullRewrite)
    }

    @Test
    fun `empty nodes force a full rewrite`() {
        val plan = planNodeWrite(emptyMap(), emptyList())
        assertTrue(plan.fullRewrite)
    }
}
