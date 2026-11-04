package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.gate.Irreversible
import com.wangzan101.mika.core.gate.IrreversibleDetector
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.wangzan101.mika.core.tool.Verb

/**
 * 一个动作的风险。[hit] 是不可逆判定的结果(不用判或没命中为 null);[screen] 是判定所依据的那一屏,用到时才读。
 * 工具自己声明风险之后换掉实现即可,判定和审批不用改。
 */
fun interface RiskJudge {
    fun assess(verb: Verb?, hit: Irreversible?, screen: () -> ScreenSnapshot?): Risk
}

/** 按现有的不可逆判定和读屏信号分级;花不花钱按动作类别和屏上的金额认,不按 App。 */
object ClassifiedRisk : RiskJudge {
    /** 改界面上东西的动作;其余写操作(返回、滑动、打开 App、打开网址)只是在界面间移动。 */
    private val CHANGE_VERBS = setOf(Verb.Tap, Verb.TypeText, Verb.ClearText, Verb.PressKey, Verb.Upload, Verb.Interact)

    /** 这个动作会改界面上的东西(不只是在界面间移动);不可逆、对外的动作也都是这几种动作做出来的。 */
    fun changesState(verb: Verb?): Boolean = verb in CHANGE_VERBS

    override fun assess(verb: Verb?, hit: Irreversible?, screen: () -> ScreenSnapshot?): Risk {
        val level = when (hit?.action) {
            null -> if (verb in CHANGE_VERBS) ActionRisk.Change else ActionRisk.Read
            IrrevAction.send, IrrevAction.submit -> ActionRisk.External
            IrrevAction.pay, IrrevAction.order, IrrevAction.delete -> ActionRisk.Irreversible
            IrrevAction.other -> ActionRisk.Unknown
        }
        if (hit == null) return Risk(level)
        // 金额是页面内容,不等于这一步要花钱。明确付款/下单始终拦;其余动作要有合计或付款面板等应付上下文。
        val payment = when (hit.action) {
            IrrevAction.pay, IrrevAction.order -> true
            IrrevAction.send -> false
            else -> screen()?.let(IrreversibleDetector::hasPayableAmount) == true
        }
        return Risk(level, payment)
    }

    /** 动作落在哪个元素上:带编号的按编号找,打字落在有焦点的输入框,坐标点击取包含该点的最小元素。 */
    fun target(verb: Verb?, args: JsonObject, s: ScreenSnapshot): UiElement? {
        fun str(k: String) = (args[k] as? JsonPrimitive)?.contentOrNull
        str("ref")?.let { ref -> return s.elements.firstOrNull { it.ref == ref } }
        if (verb == Verb.TypeText || verb == Verb.ClearText) return s.elements.firstOrNull { it.focused && it.editable }
        val x = str("x")?.toDoubleOrNull() ?: return null
        val y = str("y")?.toDoubleOrNull() ?: return null
        return s.elements.filter { e -> e.bounds.size == 4 && x >= e.bounds[0] && x <= e.bounds[2] && y >= e.bounds[1] && y <= e.bounds[3] }
            .minByOrNull { (it.bounds[2] - it.bounds[0]).toLong() * (it.bounds[3] - it.bounds[1]) }
    }
}
