package com.posysaev.socialbridge.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.math.min
import kotlin.random.Random

@Component
class RetryService {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Универсальный ретрай с экспоненциальной задержкой и джиттером.
     *
     * @param times           кол-во попыток (включая первую)
     * @param initialDelayMs  стартовая задержка между попытками
     * @param maxDelayMs      потолок задержки
     * @param factor          во сколько раз растёт задержка
     * @param jitterRatio     случайный разброс задержки 0..jitterRatio (0.0 — без джиттера)
     * @param retryOn         функция, которая решает, повторять ли при данном исключении
     * @param onRetry         колбэк перед повторами (для логов/метрик)
     * @param block           действие, которое ретраим
     */
    fun <T> retry(
        times: Int = 3,
        initialDelayMs: Long = 1_000,
        maxDelayMs: Long = 5_000,
        factor: Double = 2.0,
        jitterRatio: Double = 0.25,
        retryOn: (Throwable) -> Boolean = { true },
        onRetry: (attempt: Int, delayMs: Long, cause: Throwable) -> Unit = { _, _, _ -> },
        block: () -> T
    ): T {
        require(times >= 1) { "times must be >= 1" }

        var attempt = 1
        var delay = initialDelayMs
        var lastError: Throwable? = null

        while (attempt <= times) {
            try {
                return block()
            } catch (e: Throwable) {
                lastError = e
                if (attempt == times || !retryOn(e)) break

                val jitter = if (jitterRatio > 0) {
                    (delay * Random.nextDouble(0.0, jitterRatio)).toLong()
                } else 0L
                val sleep = delay + jitter
                onRetry(attempt + 1, sleep, e)
                log.warn("RetryService: attempt {} in {} ms (reason: {})", attempt + 1, sleep, e.message)

                try {
                    Thread.sleep(sleep)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }

                delay = min((delay * factor).toLong(), maxDelayMs)
                attempt++
            }
        }
        throw lastError ?: IllegalStateException("Retry failed without exception")
    }
}
