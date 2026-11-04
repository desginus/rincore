package com.wangzan101.mika.core.uistate

/** 站点知识、App 知识里 `pace` 说的这个网站或 App 对浏览快慢的要求(App 里的节奏见 [com.wangzan101.mika.core.cap.phone.AppPacing])。 */
enum class PlacePace {
    /** 没有特别说法。 */
    NORMAL,
    /** 这个网站或 App 限制浏览快慢(看得太快、太多会被拦):每页停得更久、一个任务里看的页、屏更少。 */
    CAREFUL;

    companion object {
        /** 站点知识头部的值(`careful`;不写为 null)。 */
        fun of(value: String?): PlacePace = if (value == "careful") CAREFUL else NORMAL
    }
}
