package com.example.samples.s26.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The expiry spread, measured rather than assumed.
 *
 * <p>Jitter is the half of the avalanche problem that single flight does not touch, and it is easy to add
 * in a form that does nothing — a ratio applied to seconds instead of millis, or a random draw whose range
 * collapses to zero for short TTLs. So the distribution is checked: inside the band, and actually varying.
 */
class TtlJitterTest {

  private static final int SAMPLES = 500;

  @Test
  void everyDrawIsInsideTheBand() {
    CachingQueryInterceptor interceptor = interceptorWith(0.1);
    Duration base = Duration.ofSeconds(60);

    for (int i = 0; i < SAMPLES; i++) {
      Duration jittered = interceptor.jittered(base);
      assertThat(jittered).isBetween(Duration.ofSeconds(54), Duration.ofSeconds(66));
    }
  }

  /**
   * And the draws differ.
   *
   * <p>The control for the test above, which a constant function would also pass. 500 draws over a
   * 12-second band at millisecond resolution: anything under a few hundred distinct values means the
   * spread is not happening.
   */
  @Test
  void thedrawsAreSpreadOut() {
    CachingQueryInterceptor interceptor = interceptorWith(0.1);
    Set<Long> seen = new HashSet<>();

    for (int i = 0; i < SAMPLES; i++) {
      seen.add(interceptor.jittered(Duration.ofSeconds(60)).toMillis());
    }

    assertThat(seen).hasSizeGreaterThan(SAMPLES / 2);
  }

  /** Zero means off, exactly — not "a very small spread". */
  @Test
  void aratioOfZeroLeavesTheTtlAlone() {
    CachingQueryInterceptor interceptor = interceptorWith(0);

    assertThat(interceptor.jittered(Duration.ofSeconds(60))).isEqualTo(Duration.ofSeconds(60));
  }

  /**
   * A TTL too short for the ratio to move is left alone rather than randomised into zero.
   *
   * <p>10% of 5ms is 0ms, and a random draw over an empty range throws. Worth a test because it is the
   * kind of edge a jitter helper meets only in someone else's configuration.
   */
  @Test
  void attlTooShortToJitterIsLeftAlone() {
    CachingQueryInterceptor interceptor = interceptorWith(0.1);

    assertThat(interceptor.jittered(Duration.ofMillis(5))).isEqualTo(Duration.ofMillis(5));
  }

  private static CachingQueryInterceptor interceptorWith(double ratio) {
    CacheSettings settings = new CacheSettings();
    settings.setJitterRatio(ratio);
    return new CachingQueryInterceptor(
        new NoCache(), new ObjectMapper(), settings, new CacheTelemetry());
  }

  /** Never asked anything in this test; present because the constructor needs it. */
  private static final class NoCache implements QueryCache {

    @Override
    public Optional<String> get(String key) {
      return Optional.empty();
    }

    @Override
    public void put(String key, String value, Duration ttl) {}

    @Override
    public void evict(String key) {}

    @Override
    public int evictMatching(String pattern) {
      return 0;
    }

    @Override
    public Optional<Duration> timeToLive(String key) {
      return Optional.empty();
    }
  }
}
