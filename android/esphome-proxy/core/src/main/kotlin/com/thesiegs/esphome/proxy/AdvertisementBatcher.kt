package com.thesiegs.esphome.proxy

/**
 * Groups advertisements the way ESPHome's bluetooth_proxy does: a batch goes out when it holds
 * [maxBatch] advertisements or its oldest one has waited [maxDelayMs]. Not thread-safe; the server
 * guards it. Time comes in from the caller so tests need no clock.
 */
class AdvertisementBatcher(private val maxBatch: Int = 16, private val maxDelayMs: Long = 100) {
    private val pending = ArrayList<RawAdvertisement>(maxBatch)
    private var firstAt = 0L

    val size get() = pending.size

    /** Adds one; returns a full batch to send now, or null. */
    fun add(ad: RawAdvertisement, nowMs: Long): List<RawAdvertisement>? {
        if (pending.isEmpty()) firstAt = nowMs
        pending += ad
        return if (pending.size >= maxBatch) drain() else null
    }

    /** Returns the pending batch once its oldest advertisement is due, else null. */
    fun poll(nowMs: Long): List<RawAdvertisement>? =
        if (pending.isNotEmpty() && nowMs - firstAt >= maxDelayMs) drain() else null

    fun clear() = pending.clear()

    private fun drain(): List<RawAdvertisement> = ArrayList(pending).also { pending.clear() }
}
