package me.rerere.rikkahub.data.datastore

import java.lang.reflect.Modifier
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★v5.3.58 防再犯护栏：**每个 Settings 属性都必须登记进 [SETTINGS_FIELDS]**。
 *
 * 为什么需要这条测试：Settings 是 `DataStore<Preferences>` + **手动字段映射** ——
 * 不在 [SETTINGS_FIELDS] 里的属性**只活在内存里，进程重启就回到默认值**。
 * 这个坑已经踩了至少四次，用户侧表现一模一样（「我选好了，重启又变回去」）：
 *
 *  · v5.1.4  `fastChatModelIds` / `ultraChatModelIds`（快速/极致模型池）
 *  · v5.0.67 `dreamMemoryModelId`（做梦记忆模型）—— 用户 2026-11-07 再次报告
 *  · v5.1.9  `swapSendAndReasoning`（交换发送键与思考键位置）
 *  · v5.1.3  `showSendButton` / `showReasoningButton`
 *
 * 每一次都是「字段加了、UI 能改、就是存不住」，而 CI 只验编译，永远发现不了。
 * 这条测试用反射枚举 **Settings 的全部字段**与登记表求差集 —— 以后任何新字段忘了登记，
 * 都会在这里红，而不是等用户装上去发现。
 */
class SettingsPersistenceTest {

    @Test
    fun `every Settings property is registered in SETTINGS_FIELDS`() {
        val registered = SETTINGS_FIELDS.map { it.property.name }.toSet()

        val declared = Settings::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .map { it.name }
            .toSet()

        val missing = (declared - registered).sorted()

        assertTrue(
            "以下设置项没有登记进 SETTINGS_FIELDS，会「内存有效、重启即丢」：$missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `SETTINGS_FIELDS has no duplicate keys or properties`() {
        val keys = SETTINGS_FIELDS.map { it.key.name }
        val properties = SETTINGS_FIELDS.map { it.property.name }
        assertTrue("同一个 DataStore key 被登记了两次：${keys.groupBy { it }.filterValues { it.size > 1 }.keys}", keys.size == keys.distinct().size)
        assertTrue("同一个属性被登记了两次：${properties.groupBy { it }.filterValues { it.size > 1 }.keys}", properties.size == properties.distinct().size)
    }
}
