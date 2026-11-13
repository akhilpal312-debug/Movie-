package com.jarvis.assistant.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ActionRateLimiterTest {

    @Test
    fun `allows exactly the configured number of actions per minute`() {
        var now = 5_000_000L
        val limiter = ActionRateLimiter { now }

        assertThat(limiter.tryAcquire(3)).isTrue()
        assertThat(limiter.tryAcquire(3)).isTrue()
        assertThat(limiter.tryAcquire(3)).isTrue()
        assertThat(limiter.tryAcquire(3)).isFalse()
    }

    @Test
    fun `slides the window forward`() {
        var now = 5_000_000L
        val limiter = ActionRateLimiter { now }

        assertThat(limiter.tryAcquire(1)).isTrue()
        assertThat(limiter.tryAcquire(1)).isFalse()

        now += 59_000
        assertThat(limiter.tryAcquire(1)).isFalse()

        now += 2_000
        assertThat(limiter.tryAcquire(1)).isTrue()
    }

    @Test
    fun `a reset clears the window`() {
        val limiter = ActionRateLimiter { 1_000L }
        assertThat(limiter.tryAcquire(1)).isTrue()
        assertThat(limiter.tryAcquire(1)).isFalse()

        limiter.reset()
        assertThat(limiter.tryAcquire(1)).isTrue()
    }
}
