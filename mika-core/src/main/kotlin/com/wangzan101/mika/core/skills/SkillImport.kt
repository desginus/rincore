package com.wangzan101.mika.core.skills

import com.wangzan101.mika.core.knowledge.FrontMatter

/**
 * 别人分享来的一份 SKILL.md 变成待收下的草稿(#165)。不经过模型,也不直接进试用:要用户在「待确认」里看过、收下才用。
 * 出处由代码写成导入([PersonalSkill.SOURCE_IMPORTED]),自学用的几行去掉;格式、大小、敏感内容过和自学草稿同一道校验
 * ([SkillDrafter.validate])。内置技能的名字不能用;同名的个人技能已有时成为它的更新草稿,收下前能看到改了哪几行。
 */
object SkillImport {
    sealed interface Result {
        /** 放进待收下时用的编号和草稿。 */
        data class Ready(val id: String, val skill: PersonalSkill) : Result
        /** 不收:[problem] 是 [SkillDrafter.validate] 的码,或 `builtin_name`、`draft_pending`。 */
        data class Rejected(val problem: String) : Result
    }

    /** 本机才有意义的几行(出处、学自哪次运行、更新指向);分享出去时去掉,导入时由代码重写。 */
    private val LOCAL_FIELDS = listOf("updates", "learned_from", "source")

    /** 分享出去的全文:去掉 [LOCAL_FIELDS],其余原样。 */
    fun shareable(markdown: String): String =
        LOCAL_FIELDS.fold(markdown.replace("\r\n", "\n")) { m, k -> PersonalSkills.withoutField(m, k) }

    fun prepare(
        raw: String, store: PersonalSkills, builtinNames: Set<String>,
        toolNames: Set<String>, scriptTool: String?, runnableScripts: Set<String> = emptySet(),
        id: String, now: Long,
    ): Result {
        if (raw.length > SkillDrafter.MAX_CHARS * 2) return Result.Rejected("too_long")
        val md = PersonalSkills.withField(shareable(SkillDrafter.stripFence(raw)), "source", PersonalSkill.SOURCE_IMPORTED)
        SkillDrafter.validate(md, toolNames, scriptTool, runnableScripts)?.let { return Result.Rejected(it) }
        val name = FrontMatter.parse(md).first["name"] as String
        if (name in builtinNames) return Result.Rejected("builtin_name")
        if (store.pending().any { it.name == name || it.replacesDraftOf == name }) return Result.Rejected("draft_pending")
        val existing = store.get(name)
        return Result.Ready(id, PersonalSkill(
            name = name, markdown = md, status = SkillStatus.pending,
            learnedFrom = null, replacesDraftOf = existing?.name,
            createdAt = now, outcomes = emptyList(),
            baseHash = existing?.let { PersonalSkills.fingerprint(it.markdown) },
            source = PersonalSkill.SOURCE_IMPORTED,
        ))
    }
}
