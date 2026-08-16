package me.rerere.rikkahub.web


/* ───【原版对齐】Exceptions.kt | 差异 ±1 行
 * 来源: 原版移植 + 自研小调整 (未达专项标注阈值, 对齐细节见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import io.ktor.http.HttpStatusCode

sealed class ApiException(
    override val message: String,
    val status: HttpStatusCode
) : RuntimeException(message)

class BadRequestException(message: String) : ApiException(message, HttpStatusCode.BadRequest)
class NotFoundException(message: String) : ApiException(message, HttpStatusCode.NotFound)
class UnauthorizedException(message: String) : ApiException(message, HttpStatusCode.Unauthorized)
class ConflictException(message: String) : ApiException(message, HttpStatusCode.Conflict)
