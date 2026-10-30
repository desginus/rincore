package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * v5.3.11：按**当前**会话层 API 重写。
 *
 * 旧版本（v5.0.x 时代）用了 `store =` 构造参数与 `session.update { }`、
 * `session.acquire()`（Session 级）等早已不存在的 API —— 因为 CI 从不运行
 * app 模块单测（v5.3.10 起接通），这份测试长期编译不过且无人知晓。
 *
 * 本测试只覆盖**当前架构的核心不变量**，不追求覆盖度：
 *  1. 同一对话复用同一 session，不同对话互相隔离
 *  2. `get` / `snapshot` 与 `getOrCreate` 一致
 *  3. `cleanup` 清空所有会话
 *  4. `apply` 立即生效且对 `state` 可观测（原子读改写契约）
 */
class ConversationSessionManagerTest {

    private class Rig {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val manager = ConversationSessionManager(
            scope = scope,
            createInitialConversation = { Conversation.ofId(it) },
            onGenerationFinished = { _, _ -> },
        )

        fun close() {
            manager.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `same conversation shares a session while different conversations stay isolated`() {
        val rig = Rig()
        try {
            val id = Uuid.random()
            val first = rig.manager.getOrCreate(id)
            val other = rig.manager.getOrCreate(Uuid.random())

            assertSame(first, rig.manager.getOrCreate(id))
            assertNotSame(first, other)
            assertEquals(2, rig.manager.snapshot().size)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `get returns the created session and null for unknown conversations`() {
        val rig = Rig()
        try {
            val id = Uuid.random()
            val created = rig.manager.getOrCreate(id)

            assertSame(created, rig.manager.get(id))
            assertNull(rig.manager.get(Uuid.random()))
        } finally {
            rig.close()
        }
    }

    @Test
    fun `apply writes through immediately and is observable from state`() {
        val rig = Rig()
        try {
            val id = Uuid.random()
            val session = rig.manager.getOrCreate(id)

            session.apply { it.copy(title = "first") }

            assertEquals("first", session.state.value.title)
            assertEquals("first", rig.manager.getOrCreate(id).state.value.title)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `cleanup removes every session`() {
        val rig = Rig()
        try {
            rig.manager.getOrCreate(Uuid.random())
            rig.manager.getOrCreate(Uuid.random())
            assertEquals(2, rig.manager.snapshot().size)

            rig.manager.cleanup()

            assertEquals(0, rig.manager.snapshot().size)
        } finally {
            rig.scope.cancel()
        }
    }

    @Test
    fun `initialize loads the authoritative conversation and marks the session ready`() = runBlocking {
        val rig = Rig()
        try {
            val id = Uuid.random()
            val session = rig.manager.getOrCreate(id)
            val stored = Conversation.ofId(id).copy(title = "from-db")

            session.initialize(load = { stored })

            assertNotNull(rig.manager.get(id))
            assertEquals("from-db", session.state.value.title)
            assertEquals(true, session.isReady)
        } finally {
            rig.close()
        }
    }
}
