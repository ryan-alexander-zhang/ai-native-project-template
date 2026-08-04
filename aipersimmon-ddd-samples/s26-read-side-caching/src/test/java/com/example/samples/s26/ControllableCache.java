package com.example.samples.s26;

import com.example.samples.s26.catalog.application.QueryCache;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The real Redis cache with one knob: a {@link Controlled#holdPutsUntil} gate that stalls a write.
 *
 * <p>It exists for one test — the one showing that evicting after the commit narrows the stale-entry
 * window without closing it. That window needs a reader's cache write to land <em>after</em> a writer's
 * eviction, and there is no way to arrange that from outside, because the write happens inside the
 * interceptor. So the test stalls it here, in the real path, with the real value the real read produced.
 *
 * <p>Nothing is faked: {@code get}, {@code put}, {@code evict} all reach Redis. The gate is null by
 * default, so every other test in this context sees the unmodified cache.
 *
 * <p>The delegate is injected by bean name rather than by type — the production adapter is package-private
 * in {@code infrastructure} and this decorator has no business seeing its class.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ControllableCache {

  @Bean
  @Primary
  Controlled controlledQueryCache(@Qualifier("redisQueryCache") QueryCache delegate) {
    return new Controlled(delegate);
  }

  /** A pass-through that can be told to hold its writes. */
  public static class Controlled implements QueryCache {

    private final QueryCache delegate;

    private volatile CountDownLatch gate;
    private volatile CountDownLatch reachedPut;

    Controlled(QueryCache delegate) {
      this.delegate = delegate;
    }

    /**
     * Stall the next {@code put} until {@code gate} opens.
     *
     * @param reached counted down when a {@code put} arrives, so the test knows the reader is parked
     */
    public void holdPutsUntil(CountDownLatch gate, CountDownLatch reached) {
      this.gate = gate;
      this.reachedPut = reached;
    }

    /** Back to an ordinary cache. */
    public void reset() {
      this.gate = null;
      this.reachedPut = null;
    }

    @Override
    public Optional<String> get(String key) {
      return delegate.get(key);
    }

    @Override
    public void put(String key, String value, Duration ttl) {
      CountDownLatch waitFor = gate;
      if (waitFor != null) {
        CountDownLatch arrived = reachedPut;
        if (arrived != null) {
          arrived.countDown();
        }
        try {
          // Bounded: a test that deadlocks here would hang the build rather than fail it.
          if (!waitFor.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the put gate was never opened");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("interrupted while holding a cache write", interrupted);
        }
      }
      delegate.put(key, value, ttl);
    }

    @Override
    public void evict(String key) {
      delegate.evict(key);
    }

    @Override
    public int evictMatching(String pattern) {
      return delegate.evictMatching(pattern);
    }

    @Override
    public Optional<Duration> timeToLive(String key) {
      return delegate.timeToLive(key);
    }
  }
}
