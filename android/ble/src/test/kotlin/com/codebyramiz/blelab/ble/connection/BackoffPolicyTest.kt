package com.codebyramiz.blelab.ble.connection

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertFailsWith

class BackoffPolicyTest {
    @Test
    fun `doubles from the initial delay up to the cap`() {
        val policy = BackoffPolicy(initialDelayMillis = 500, maxDelayMillis = 4_000, jitterRatio = 0.0)
        assertThat((1..6).map(policy::delayFor)).containsExactly(500L, 1_000L, 2_000L, 4_000L, 4_000L, 4_000L).inOrder()
    }

    @Test
    fun `jitter stays within the ratio`() {
        val policy = BackoffPolicy(initialDelayMillis = 1_000, maxDelayMillis = 16_000, jitterRatio = 0.2, random = Random(3))
        repeat(200) { assertThat(policy.delayFor(1)).isIn(800L..1_200L) }
    }

    @Test
    fun `validates arguments`() {
        assertFailsWith<IllegalArgumentException> { BackoffPolicy(initialDelayMillis = 0) }
        assertFailsWith<IllegalArgumentException> { BackoffPolicy(jitterRatio = 2.0) }
        assertFailsWith<IllegalArgumentException> { BackoffPolicy(maxAttempts = 0) }
        assertFailsWith<IllegalArgumentException> { BackoffPolicy().delayFor(0) }
    }
}
