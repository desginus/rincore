package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.BARRIER_KINDS
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.ok
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.put

/** 说出挡住当前 App 或网页的是什么,拿到该怎么处理的下一步。 */
internal object HandleObstacle : SurfaceAware {
    override val verb = Verb.HandleObstacle
    override val label = "遇到障碍"
    override val risk = Risk.Read
    override val withScreens = true
    override val def = ToolDef("handle_obstacle",
        Prompts.tool("handle_obstacle"),
        mapOf("kind" to str("What is in the way: popup = a promotion, guide or update dialog that can be closed; login = a sign-in wall; captcha = a human-verification puzzle; access_blocked = the app or site is limiting access (操作频繁, 环境异常, or a verification that keeps coming back); payment = paying, entering a password or an identity check.", BARRIER_KINDS),
            "reason" to str("What on the screen shows it.")),
        listOf("kind"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        checkSurface(a, ctx)
        val k = a.str("kind")!!
        if (k !in BARRIER_KINDS) throw ArgError("kind", "must be one of ${BARRIER_KINDS.joinToString("|")}")
        a.str("reason", max = 300)
        return ok { put("kind", k); put("hint", hint(k)) }
    }

    private fun hint(kind: String): String = when (kind) {
        "popup" -> Feedback["barrier.popup"]
        "login" -> Feedback["barrier.login"]
        "captcha" -> Feedback["barrier.captcha"]
        "access_blocked" -> Feedback["barrier.access_blocked"]
        else -> Feedback["barrier.payment"]
    }
}
