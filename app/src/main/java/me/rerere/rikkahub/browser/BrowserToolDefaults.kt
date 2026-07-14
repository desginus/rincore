package me.rerere.rikkahub.browser

/**
 * Authoritative list of the 18 browser tools the LLM can drive, plus their default
 * enabled/disabled state. Read tools (cheap, don't touch the page) default ON;
 * write tools (mutate state, can be misused) default OFF; the loop-control tool
 * defaults ON because the AI can't escape the browser loop without it.
 */
object BrowserToolDefaults {

    // --- Read tools (default ON) --------------------------------------------------------------
    const val OPEN = "browser_open"
    const val CURRENT_URL = "browser_current_url"
    const val SCREENSHOT = "browser_screenshot"
    const val GET_TEXT = "browser_get_text"
    const val GET_DOM = "browser_get_dom"
    const val GET_LINKS = "browser_get_links"
    const val BACK = "browser_back"
    const val FORWARD = "browser_forward"
    const val WAIT_FOR = "browser_wait_for"

    // --- Write tools (default OFF) -----------------------------------------------------------
    const val CLICK = "browser_click"
    const val TYPE = "browser_type"
    const val SCROLL = "browser_scroll"
    const val SUBMIT = "browser_submit"
    const val SELECT = "browser_select"
    const val PRESS_KEY = "browser_press_key"
    const val EVAL_JS = "browser_eval_js"
    const val CLICK_AND_READ = "browser_click_and_read"

    // --- Loop control (default ON) -----------------------------------------------------------
    const val DONE = "browser_done"

    val READ_TOOLS: Set<String> = setOf(
        OPEN, CURRENT_URL, SCREENSHOT, GET_TEXT, GET_DOM, GET_LINKS, BACK, FORWARD, WAIT_FOR,
    )

    val WRITE_TOOLS: Set<String> = setOf(
        CLICK, TYPE, SCROLL, SUBMIT, SELECT, PRESS_KEY, EVAL_JS, CLICK_AND_READ,
    )

    val LOOP_CONTROL_TOOLS: Set<String> = setOf(DONE)

    /** Stable display order. Read first, then write, then loop-control. */
    val ALL_TOOLS: List<String> = listOf(
        OPEN, CURRENT_URL, SCREENSHOT, GET_TEXT, GET_DOM, GET_LINKS, BACK, FORWARD, WAIT_FOR,
        CLICK, TYPE, SCROLL, SUBMIT, SELECT, PRESS_KEY, EVAL_JS, CLICK_AND_READ,
        DONE,
    )

    val DEFAULT_ENABLED: Map<String, Boolean> = buildMap {
        READ_TOOLS.forEach { put(it, true) }
        LOOP_CONTROL_TOOLS.forEach { put(it, true) }
        WRITE_TOOLS.forEach { put(it, false) }
    }

    enum class Category { READ, WRITE, LOOP_CONTROL }

    fun category(toolName: String): Category = when {
        toolName in WRITE_TOOLS -> Category.WRITE
        toolName in LOOP_CONTROL_TOOLS -> Category.LOOP_CONTROL
        else -> Category.READ
    }

    // --- Timeout settings -------------------------------------------------------------------
    const val DEFAULT_PER_TOOL_TIMEOUT_MS = 30_000L
    const val MIN_PER_TOOL_TIMEOUT_MS = 10_000L
    const val MAX_PER_TOOL_TIMEOUT_MS = 600_000L

    const val DEFAULT_SINGLE_TASK_TIMEOUT_MS = 300_000L
    const val MIN_SINGLE_TASK_TIMEOUT_MS = 60_000L
    const val MAX_SINGLE_TASK_TIMEOUT_MS = 3_600_000L

    fun clampPerToolTimeoutMs(ms: Long): Long =
        ms.coerceIn(MIN_PER_TOOL_TIMEOUT_MS, MAX_PER_TOOL_TIMEOUT_MS)

    fun clampSingleTaskTimeoutMs(ms: Long): Long =
        ms.coerceIn(MIN_SINGLE_TASK_TIMEOUT_MS, MAX_SINGLE_TASK_TIMEOUT_MS)
}
