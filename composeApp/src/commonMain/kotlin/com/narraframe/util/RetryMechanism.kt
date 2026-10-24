package com.narraframe.util

import kotlinx.coroutines.delay

data class RetryConfig(
    val maxAttempts:  Int            = 3,
    val initialDelay: Long           = 1_000L,
    val multiplier:   Double         = 2.0,
    val maxDelay:     Long           = 30_000L
) {
    companion object {
        val default    = RetryConfig()
        val aggressive = RetryConfig(maxAttempts = 5, initialDelay = 500)
        val once       = RetryConfig(maxAttempts = 1)
    }
}

class RetryExhaustedException(val lastCause: Throwable) :
    Exception("All retry attempts exhausted. Last error: ${lastCause.message}", lastCause)

/**
 * Retries [block] up to [config.maxAttempts] times with exponential back-off.
 * Logs each attempt through [AppLogger].
 */
suspend fun <T> withRetry(
    operation: String,
    config:    RetryConfig = RetryConfig.default,
    block:     suspend () -> T
): T {
    var lastError: Throwable? = null
    var delayMs = config.initialDelay

    repeat(config.maxAttempts) { attempt ->
        try {
            val result = block()
            if (attempt > 0) AppLogger.success("$operation – succeeded on attempt ${attempt + 1}")
            return result
        } catch (e: Throwable) {
            lastError = e
            AppLogger.warning("$operation – attempt ${attempt + 1} failed: [${e::class.simpleName}] ${e.message ?: "(no message)"}")
            if (attempt < config.maxAttempts - 1) {
                AppLogger.warning("$operation – retry ${attempt + 2}/${config.maxAttempts} in ${delayMs}ms")
                delay(delayMs)
                delayMs = minOf((delayMs * config.multiplier).toLong(), config.maxDelay)
            }
        }
    }
    throw RetryExhaustedException(lastError!!)
}
