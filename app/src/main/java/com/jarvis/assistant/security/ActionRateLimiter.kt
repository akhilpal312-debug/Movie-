package com.jarvis.assistant.security

/**
 * Bounds how many actions can be authorised in a rolling minute. This protects the
 * user from a looping model, a runaway routine or a retry storm hammering the device.
 */
class ActionRateLimiter(private val clock: () -> Long = System::currentTimeMillis) {

    private val timestamps = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(maxPerMinute: Int): Boolean {
        val now = clock()
        val windowStart = now - 60_000L
        while (timestamps.isNotEmpty() && timestamps.first() <= windowStart) {
            timestamps.removeFirst()
        }
        if (timestamps.size >= maxPerMinute.coerceAtLeast(1)) return false
        timestamps.addLast(now)
        return true
    }

    @Synchronized
    fun reset() = timestamps.clear()
}
