package com.narraframe.services

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

// ── Shared client factory ──────────────────────────────────────────────────
fun buildHttpClient(): HttpClient = HttpClient {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient         = true
        })
    }
    install(Logging) {
        logger = object : Logger {
            override fun log(message: String) {
                com.narraframe.util.AppLogger.debug("[HTTP] $message")
            }
        }
        level = LogLevel.INFO
    }
    install(HttpTimeout) {
        requestTimeoutMillis  = 120_000
        connectTimeoutMillis  = 30_000
        socketTimeoutMillis   = 120_000
    }
}

// ── Error model ────────────────────────────────────────────────────────────
sealed class ApiError : Exception() {
    data class Unauthorized(override val message: String  = "Unauthorized – check your API key") : ApiError()
    data class RateLimited (val retryAfterSecs: Int?     = null)                                 : ApiError() {
        override val message = "Rate limited${retryAfterSecs?.let { " – retry after ${it}s" } ?: ""}"
    }
    data class ServerError (val statusCode: Int, override val message: String)                   : ApiError()
    data class ClientError (val statusCode: Int, override val message: String)                   : ApiError()
    data class NetworkError(override val message: String)                                        : ApiError()
    data class ParseError  (override val message: String, val cause2: Throwable)                 : ApiError()
}

// ── Response guard ─────────────────────────────────────────────────────────
suspend fun HttpResponse.guardSuccess(): HttpResponse {
    return when (status.value) {
        in 200..299 -> this
        401         -> throw ApiError.Unauthorized()
        429         -> {
            val after = headers["Retry-After"]?.toIntOrNull()
            throw ApiError.RateLimited(after)
        }
        in 500..599 -> throw ApiError.ServerError(status.value, bodyAsText().take(200))
        else        -> throw ApiError.ClientError(status.value, bodyAsText().take(200))
    }
}
