/* 【域 B·AI 传输】 | 地图: docs/APP_MAP.md §B */
package me.rerere.rikkahub.data.ai.transformers


/* ───【原版对齐】OcrTransformer.kt | 差异 ±2 行
 * 来源: 原版移植 + 自研小调整 (未达专项标注阈值, 对齐细节见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.cache.LruCache
import me.rerere.common.cache.SingleFileCacheStore
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import kotlin.time.Duration.Companion.days

private const val TAG = "OcrTransformer"

object OcrTransformer : InputMessageTransformer, KoinComponent {
    private val cache by lazy {
        val context = get<Context>()
        val json = Json { allowStructuredMapKeys = true }
        val store = SingleFileCacheStore(
            file = File(context.cacheDir, "ocr_cache.json"),
            keySerializer = String.serializer(),
            valueSerializer = String.serializer(),
            json = json
        )
        LruCache(
            // v4.8.107: 64 → 256 —— 含图长对话在逐出后会重新 OCR(结果非确定)导致
            // 请求字节逐轮漂移 (prompt cache 全崩的根因之一), 扩容防逐出。
            capacity = 256,
            store = store,
            deleteOnEvict = true,
            preloadFromStore = true,
            expireAfterWriteMillis = 3.days.inWholeMilliseconds,
        )
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (ctx.model.inputModalities.contains(Modality.IMAGE)) {
            return messages
        }

        val hasImages = messages.any { message ->
            message.parts.any { it is UIMessagePart.Image && it.url.startsWith("file:") }
        }
        if (!hasImages) return messages

        return withContext(Dispatchers.IO) {
            try {
                ctx.processingStatus.value = "正在识别图片..."
                messages.map { message ->
                    message.copy(
                        parts = message.parts.map { part ->
                            when {
                                part is UIMessagePart.Image && part.url.startsWith("file:") -> {
                                    UIMessagePart.Text(performOcr(part))
                                }

                                else -> part
                            }
                        }
                    )
                }
            } finally {
                ctx.processingStatus.value = null
            }
        }
    }

    /**
     * v4.8.107 (缓存稳定性根治): 图片来源文本必须"逐轮字节恒定"——
     * DeepSeek 等非视觉模型下, 每轮请求构建都会把历史图片经本函数替换为文本,
     * 其结果若逐轮变化, prompt cache 会从图片位置起永久崩裂 (用户实证:
     * 上传图片后缓存只命中 ~11.4K ≈ 系统提示)。
     *
     * 稳定性三重保障 (缺一不可):
     *   ①成功结果入 3 天缓存 (LRU 256), 跨请求/跨轮零重算;
     *   ②**失败结果同样入缓存 (30 分钟短 TTL)** —— 旧实现失败不缓存,
     *     每轮重试且异常文本逐次不同 → 每轮字节漂移; 短 TTL 兼顾"网络恢复后
     *     可重试", 连续轮次内恒定;
     *   ③同 url 并发去重 (Mutex) —— 多轮同时 miss 时只执行一次, 不产生
     *     并行重算的多个不同结果。
     *   OCR 调用固定 temperature=0 (重算时输出尽量可复现)。
     */
    private val inflightLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    suspend fun performOcr(part: UIMessagePart.Image): String {
        cache.get(part.url)?.let { cachedResult ->
            Log.i(TAG, "performOcr: cache hit for ${part.url}")
            return cachedResult
        }
        val mutex = inflightLocks.computeIfAbsent(part.url) { kotlinx.coroutines.sync.Mutex() }
        return mutex.withLock {
            // 双检: 等待期间其它轮可能已写入
            cache.get(part.url)?.let { return@withLock it }
            val outcome = runOcrRaw(part)
            if (outcome == null) {
                // 无法识别的确定性兜底 (未配 OCR 模型等) — 恒定量, 无需缓存
                return@withLock UNKNOWN_IMAGE_PLACEHOLDER
            }
            val (text, ok) = outcome
            // v4.8.107: 成功 3 天 / 失败 30 分钟 — 失败也写, 连续轮次内字节恒定
            if (ok) cache.put(part.url, text)
            else cache.put(part.url, text, FAILURE_CACHE_TTL_MS)
            Log.i(TAG, "performOcr: cache put (ok=$ok) for ${part.url}")
            text
        }
    }

    /** @return null=确定性兜底(未配模型); 否则 (文本, 是否成功) */
    private suspend fun runOcrRaw(part: UIMessagePart.Image): Pair<String, Boolean>? {
        val settings = get<SettingsStore>().settingsFlow.value
        val model = settings.findModelById(settings.ocrModelId) ?: return null
        val providerSetting = model.findProvider(settings.providers) ?: return null
        val provider = get<ProviderManager>().getProviderByType(providerSetting)
        return runCatching {
            val result = provider.generateText(
                providerSetting = providerSetting,
                messages = listOf(
                    UIMessage.system(settings.ocrPrompt),
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Image(part.url))
                    )
                ),
                params = TextGenerationParams(
                    model = model,
                    // v4.8.107: 固定 0 温度 — 缓存失效重算时输出尽量可复现 (稳定性>创造力)
                    temperature = 0f,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            )
            // 4.2.0: TextGenerationResult 直接持 message
            val content = result.message.toText().ifBlank { "[ERROR, OCR failed]" }
            Log.i(TAG, "performOcr: $content")
            val ocrResult = """
                <image_file_ocr>
                   $content
                </image_file_ocr>
                * The image_file_ocr tag contains a description of an image that the user uploaded to you, not the user's prompt.
            """.trimIndent()
            ocrResult to true
        }.getOrElse { e ->
            // v4.8.107: 错误文本消毒 — 只留异常简明消息 (截断), 不带请求体/堆栈等
            // 逐次变化的成分; 服务端响应差异由检查: message 里通常不含 request-id,
            // 若偶含, 30 分钟后的重试才会产生新文本 (一次性, 非逐轮)
            val msg = (e.message ?: e.javaClass.simpleName).take(200)
            "[ERROR, OCR failed: $msg]" to false
        }
    }

    private companion object {
        const val UNKNOWN_IMAGE_PLACEHOLDER = "[Image]"
        const val FAILURE_CACHE_TTL_MS = 30L * 60 * 1000
    }
}
