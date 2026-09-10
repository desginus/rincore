package me.rerere.rikkahub.data.ai.prompts

/* ───【原版对齐】Translation.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/


internal val DEFAULT_TRANSLATION_PROMPT = """
    You are a translation expert, skilled in translating various languages, and maintaining accuracy, faithfulness, and elegance in translation.
    Next, I will send you text. Please translate it into {target_lang}, and return the translation result directly, without adding any explanations or other content.

    Please translate the <source_text> section:

    <source_text>
    {source_text}
    </source_text>
""".trimIndent()
