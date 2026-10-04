/* 【域 A·对话核心】 — 视频生成数据面 | 地图: docs/APP_MAP.md §A */
package me.rerere.rikkahub.data.videogen

/* ───【自研】VideoGenEndpoint.kt — 视频生成接口配置 (v4.8.102)
 * 用户直接填 URL / 模型 ID / 密钥（用户定版：不做"模型类型"系统化配置，
 * 全部在视频生成页内完成）。
 * 两个内置适配：Google Veo（Gemini API）与阿里云 HappyHorse（百炼 DashScope 异步任务）。
 * ───────────────────────────────────────────────────────────────*/

import kotlinx.serialization.Serializable

@Serializable
data class VideoGenEndpoint(
    val id: String,
    val name: String,
    val baseUrl: String,
    val modelId: String,
    val apiKey: String = "",
) {
    val configured: Boolean get() = apiKey.isNotBlank()

    /** 首帧图输入：Veo 支持 instances[].image（base64 内联）；HappyHorse 文生视频暂不支持。 */
    val supportsFirstFrame: Boolean get() = id == ID_VEO

    companion object {
        const val ID_VEO = "veo"
        const val ID_HAPPYHORSE = "happyhorse"
    }
}

object VideoGenDefaults {
    /** 出厂双接口（用户可在视频生成页内编辑 URL / 模型 ID / 密钥）。 */
    val endpoints: List<VideoGenEndpoint> = listOf(
        VideoGenEndpoint(
            id = VideoGenEndpoint.ID_VEO,
            name = "Google Veo",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            modelId = "veo-3.1-generate-preview",
        ),
        VideoGenEndpoint(
            id = VideoGenEndpoint.ID_HAPPYHORSE,
            name = "阿里云 HappyHorse",
            baseUrl = "https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1",
            modelId = "happyhorse-1.1-t2v",
        ),
    )
}
