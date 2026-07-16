/**
 * The bounded-retry policy for effect delivery and deadline firing:
 * {@link com.aipersimmon.ddd.processmanager.jdbc.retry.ProcessRetryPolicy} and its
 * {@link com.aipersimmon.ddd.processmanager.jdbc.retry.ExponentialBackoffPolicy}
 * implementation (capped exponential backoff with jitter and a maximum attempt count).
 * There is no unbounded hot retry.
 */
package com.aipersimmon.ddd.processmanager.jdbc.retry;
