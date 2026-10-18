package me.rerere.search


/* ───【原版对齐】ExaSearchService | 差异 +59 行
 * 来源: 原版移植 + 自研 (Exa 搜索增强)
 * 差异: 搜索参数/结果解析自研调整
 * ───────────────────────────────────────────────────────────────*/
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import me.rerere.search.SearchService.Companion.keyRoulette
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object ExaSearchService : SearchService<SearchServiceOptions.ExaOptions> {
    private const val MAX_EVIDENCE_TEXT_CHARACTERS = 8_000
    private const val MAX_EVIDENCE_HIGHLIGHT_CHARACTERS = 1_200
    private const val MIN_MAX_AGE_HOURS = -1
    private const val MAX_MAX_AGE_HOURS = 720
    override val name: String = "Exa"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://dashboard.exa.ai/api-keys")
            }
        ) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.ExaOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
                put("type", buildJsonObject {
                    put("type", "string")
                    put("description", "Search type: fast (quick results), auto (default, balanced), deep (synthesized answer with citations)")
                    put("enum", buildJsonArray {
                        add("fast")
                        add("auto")
                        add("deep")
                    })
                })
                put("startPublishedDate", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional ISO-8601 publication date lower bound; results are published after this date")
                })
                put("endPublishedDate", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional ISO-8601 publication date upper bound; results are published before this date")
                })
                put("includeDomains", domainArraySchema("Optional domains to include"))
                put("excludeDomains", domainArraySchema("Optional domains to exclude"))
                put("maxAgeHours", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional maximum age in hours for fetched page content; use only when content freshness matters")
                    put("minimum", MIN_MAX_AGE_HOURS)
                    put("maximum", MAX_MAX_AGE_HOURS)
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.ExaOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.ExaOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildSearchRequestBody(params, commonOptions.resultSize)
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val request = Request.Builder()
                .url("https://api.exa.ai/search")
                .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyRaw = response.body.string()
                val response = runCatching {
                    json.decodeFromString<ExaData>(bodyRaw)
                }.onFailure {
                    it.printStackTrace()
                    println(bodyRaw)
                    error("Failed to decode response: $bodyRaw")
                }.getOrThrow()

                return@withContext Result.success(mapSearchResult(response))
            } else {
                println(response.body.string())
                error("response failed #${response.code}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.ExaOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for Exa"))
    }

    internal fun buildSearchRequestBody(
        params: JsonObject,
        resultSize: Int,
    ) = buildJsonObject {
        val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
        val maxAgeHours = optionalMaxAgeHours(params)
        val hasEvidenceOptions = hasOptionalString(params, "startPublishedDate") ||
            hasOptionalString(params, "endPublishedDate") ||
            hasOptionalStringArray(params, "includeDomains") ||
            hasOptionalStringArray(params, "excludeDomains") ||
            maxAgeHours != null

        put("query", JsonPrimitive(query))
        put("numResults", JsonPrimitive(resultSize))
        put("type", JsonPrimitive(params["type"]?.jsonPrimitive?.content ?: "auto"))
        putOptionalString(this, params, "startPublishedDate")
        putOptionalString(this, params, "endPublishedDate")
        putOptionalStringArray(this, params, "includeDomains")
        putOptionalStringArray(this, params, "excludeDomains")
        put("contents", buildJsonObject {
            if (hasEvidenceOptions) {
                put("text", buildJsonObject {
                    put("maxCharacters", JsonPrimitive(MAX_EVIDENCE_TEXT_CHARACTERS))
                })
                put("highlights", buildJsonObject {
                    put("maxCharacters", JsonPrimitive(MAX_EVIDENCE_HIGHLIGHT_CHARACTERS))
                })
            } else {
                put("text", JsonPrimitive(true))
            }
            maxAgeHours?.let { put("maxAgeHours", it) }
        })
    }

    internal fun mapSearchResult(data: ExaData): SearchResult = SearchResult(
        answer = data.output?.content,
        items = data.results.map {
            SearchResultItem(
                title = it.title,
                url = it.url,
                text = it.text ?: "",
                publishedDate = it.publishedDate,
                highlights = it.highlights.orEmpty(),
            )
        },
        images = data.results.mapNotNull { it.image?.takeIf { url -> url.isNotBlank() } },
    )

    private fun domainArraySchema(description: String) = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", buildJsonObject {
            put("type", "string")
        })
    }

    private fun putOptionalString(
        builder: kotlinx.serialization.json.JsonObjectBuilder,
        params: JsonObject,
        name: String,
    ) {
        params[name]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.put(name, it) }
    }

    private fun putOptionalStringArray(
        builder: kotlinx.serialization.json.JsonObjectBuilder,
        params: JsonObject,
        name: String,
    ) {
        params[name]?.jsonArray
            ?.map { it.jsonPrimitive.contentOrNull }
            ?.filter { !it.isNullOrBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { builder.put(name, buildJsonArray { it.forEach(::add) }) }
    }

    private fun hasOptionalString(params: JsonObject, name: String): Boolean =
        params[name]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true

    private fun hasOptionalStringArray(params: JsonObject, name: String): Boolean =
        params[name]?.jsonArray?.any { it.jsonPrimitive.contentOrNull?.isNotBlank() == true } == true

    private fun optionalMaxAgeHours(params: JsonObject): Int? =
        params["maxAgeHours"]?.jsonPrimitive?.intOrNull
            ?.coerceIn(MIN_MAX_AGE_HOURS, MAX_MAX_AGE_HOURS)

    @Serializable
    data class ExaData(
        @SerialName("requestId")
        val requestId: String? = null,
        @SerialName("autopromptString")
        val autopromptString: String? = null,
        @SerialName("resolvedSearchType")
        val resolvedSearchType: String? = null,
        @SerialName("results")
        val results: List<ExaResult>,
        @SerialName("output")
        val output: ExaOutput? = null,
    )

    @Serializable
    data class ExaOutput(
        @SerialName("content")
        val content: String? = null,
        @SerialName("grounding")
        val grounding: List<ExaGrounding> = emptyList(),
    )

    @Serializable
    data class ExaGrounding(
        @SerialName("field")
        val field: String? = null,
        @SerialName("citations")
        val citations: List<ExaCitation> = emptyList(),
        @SerialName("confidence")
        val confidence: String? = null,
    )

    @Serializable
    data class ExaCitation(
        @SerialName("url")
        val url: String,
        @SerialName("title")
        val title: String,
    )

    @Serializable
    data class ExaResult(
        @SerialName("id")
        val id: String,
        @SerialName("title")
        val title: String,
        @SerialName("url")
        val url: String,
        @SerialName("publishedDate")
        val publishedDate: String? = null,
        @SerialName("author")
        val author: String? = null,
        @SerialName("text")
        val text: String? = null,
        @SerialName("image")
        val image: String? = null,
        @SerialName("highlights")
        val highlights: List<String>? = null,
    )
}
