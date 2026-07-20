package com.aipersimmon.ddd.processmanager.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.processmanager.jdbc.retry.ExponentialBackoffPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The backoff computes in milliseconds, so a sub-millisecond initial must be rejected, not silently zeroed. */
class ExponentialBackoffPolicyTest {

    @Test
    void rejectsSubMillisecondInitial() {
        assertThrows(IllegalArgumentException.class, () -> new ExponentialBackoffPolicy(
                Duration.ofNanos(500_000), Duration.ofSeconds(1), 2.0, 0.0, 3),
                "a sub-ms initial would truncate to a zero delay and hot-loop");
    }

    @Test
    void oneMillisecondInitialIsHonoured() {
        ExponentialBackoffPolicy policy = new ExponentialBackoffPolicy(
                Duration.ofMillis(1), Duration.ofSeconds(1), 2.0, 0.0, 3, () -> 0.5);
        assertEquals(1L, policy.backoff(1).toMillis());
        assertEquals(2L, policy.backoff(2).toMillis());
    }
}
