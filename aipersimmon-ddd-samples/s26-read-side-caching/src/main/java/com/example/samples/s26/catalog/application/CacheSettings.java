package com.example.samples.s26.catalog.application;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The cache's policy, all of it, in one place a deployment can change without a rebuild.
 *
 * <p>TTL is deliberately configuration rather than a method on the query. A time-to-live is not a
 * property of the question being asked — it is this deployment's answer to "how stale may this get",
 * and that answer differs between a staging environment where nobody minds and a production one where
 * a price shown wrong for a minute is a complaint. Putting it in the query would make the answer a
 * code change, and would put it in the one place a caller could override it.
 */
@ConfigurationProperties("s26.cache")
public class CacheSettings {

  /** What a cached query gets when {@link #getTtl()} has no entry for it. */
  private Duration defaultTtl = Duration.ofSeconds(60);

  /** Per-query overrides, keyed by the query class's simple name. */
  private Map<String, Duration> ttl = new LinkedHashMap<>();

  /** Fraction of the TTL to spread expiries over, each way. {@code 0} disables jitter. */
  private double jitterRatio = 0.1;

  /** Whether concurrent misses on one key collapse into a single execution. */
  private boolean singleFlight = true;

  /** When an eviction runs relative to the transaction that caused it. */
  private Invalidate invalidate = Invalidate.AFTER_COMMIT;

  /**
   * The two possible moments, one of which is wrong.
   *
   * <p>{@link #IN_TRANSACTION} is the one that looks more urgent and is unsound: the entry is dropped
   * while the new value is still invisible to everybody else, so any reader in that window refills the
   * cache from the old state and the stale value then outlives the write for a whole TTL.
   * {@code InvalidationInTransactionTest} produces exactly that.
   */
  public enum Invalidate {
    AFTER_COMMIT,
    IN_TRANSACTION
  }

  /** The TTL for a query type: its override if one is configured, otherwise the default. */
  public Duration ttlFor(Class<?> queryType) {
    return ttl.getOrDefault(queryType.getSimpleName(), defaultTtl);
  }

  public Duration getDefaultTtl() {
    return defaultTtl;
  }

  public void setDefaultTtl(Duration defaultTtl) {
    this.defaultTtl = defaultTtl;
  }

  public Map<String, Duration> getTtl() {
    return ttl;
  }

  public void setTtl(Map<String, Duration> ttl) {
    this.ttl = ttl;
  }

  public double getJitterRatio() {
    return jitterRatio;
  }

  public void setJitterRatio(double jitterRatio) {
    this.jitterRatio = jitterRatio;
  }

  public boolean isSingleFlight() {
    return singleFlight;
  }

  public void setSingleFlight(boolean singleFlight) {
    this.singleFlight = singleFlight;
  }

  public Invalidate getInvalidate() {
    return invalidate;
  }

  public void setInvalidate(Invalidate invalidate) {
    this.invalidate = invalidate;
  }
}
