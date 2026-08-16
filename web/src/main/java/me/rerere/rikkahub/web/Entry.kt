package me.rerere.rikkahub.web


/* ───【原版对齐】Entry.kt | 差异 ±0 行
 * 来源: 原版移植 + 自研小调整 (未达专项标注阈值, 对齐细节见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE

fun startWebServer(
    port: Int = 8080,
    host: String = "0.0.0.0",
    module: suspend Application.() -> Unit
): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
    return embeddedServer(CIO, port = port, host = host, module = {
        install(Compression)
        install(CORS) {
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
            allowNonSimpleContentTypes = true
            anyHost()
            anyMethod()
        }
        install(SSE)
        install(DefaultHeaders)
        routing {
            staticResources("/", "static") {
                default("index.html")
                enableAutoHeadResponse()
                singlePageApplication()
            }
        }
        module()
    })
}
