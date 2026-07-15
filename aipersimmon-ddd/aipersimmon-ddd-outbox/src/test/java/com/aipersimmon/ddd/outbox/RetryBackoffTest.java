package com.aipersimmon.ddd.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * The delay grows exponentially per attempt, is capped, carries jitter within a known
 * band, and never drops to zero for a positive base — the property the fixed no-spacing
 * retry it replaces lacked.
 */
class RetryBackoffTest {

    private final RetryBackoff backoff = new RetryBackoff(1000, 60000);

    @RepeatedTest(50)
    void staysWithinTheEqualJitterBandThatDoublesPerAttempt() {
        assertWithinBand(backoff.nextDelay(1), 1000);   // cap = 1000
        assertWithinBand(backoff.nextDelay(2), 2000);   // cap = 2000
        assertWithinBand(backoff.nextDelay(3), 4000);   // cap = 4000
        assertWithinBand(backoff.nextDelay(7), 60000);  // 64000 -> capped at 60000
    }

    @RepeatedTest(50)
    void neverExceedsTheCeilingEvenForHugeAttemptCounts() {
        assertTrue(backoff.nextDelay(1000).toMillis() <= 60000, "no overflow past the ceiling");
        assertTrue(backoff.nextDelay(Integer.MAX_VALUE).toMillis() <= 60000, "no shift overflow");
    }

    @Test
    void aZeroBaseDisablesBackoff() {
        assertEquals(Duration.ZERO, new RetryBackoff(0, 0).nextDelay(5));
    }

    private static void assertWithinBand(Duration delay, long cap) {
        long millis = delay.toMillis();
        assertTrue(millis >= cap / 2, () -> "delay " + millis + "ms below half of cap " + cap);
        assertTrue(millis <= cap, () -> "delay " + millis + "ms above cap " + cap);
    }
}
