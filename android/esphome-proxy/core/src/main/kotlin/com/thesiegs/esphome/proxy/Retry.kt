package com.thesiegs.esphome.proxy

/**
 * The waits between tries of something that may fail for a while (binding the API port while
 * another app holds it, registering the mDNS record before Wi-Fi is up): [FIRST_MS], doubling, at
 * most [MAX_MS]. Pure, so tested.
 */
object Retry {
    const val FIRST_MS = 2_000L
    const val MAX_MS = 60_000L

    /** The wait before try number [attempt] + 1 (attempt 0 is the first failure). */
    fun delayMs(attempt: Int): Long = (FIRST_MS shl attempt.coerceIn(0, 10)).coerceAtMost(MAX_MS)
}
